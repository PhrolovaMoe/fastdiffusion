#!/usr/bin/env python3
"""Convert the Terrain Diffusion PyTorch checkpoints into a single GGUF file.

Input is the Hugging Face repo layout of ``xandergos/terrain-diffusion-30m``
(``{coarse,base,decoder}_model/{config.json,diffusion_pytorch_model.safetensors}``,
``config.json``) plus ``pipeline_data.json`` from ``xandergos/terrain-diffusion-30m-onnx``.

Every constant scale that the EDM2 magnitude-preserving layers apply at runtime is
folded into the stored weights, so the runtime graph is made of plain convolutions:

* MPConv weight normalisation and gain: ``w / (eps + |w| / sqrt(numel)) * gain / sqrt(fan_in)``
* ``emb_gain`` / ``out_gain`` parameters
* the ``1 / 0.596`` of ``mp_silu`` for every layer fed by an activation
* the residual-branch weight of ``mp_sum`` for layers feeding a residual sum

Attention ``qkv`` output channels are reordered from per-channel ``(q, k, v)`` triplets
into contiguous ``[q | k | v]`` blocks.

Usage: convert_to_gguf.py <weights_dir> <pipeline_data.json> <out.gguf> [--dtype f16|f32]
"""
import argparse
import json
import math
from pathlib import Path

import numpy as np
from gguf import GGUFWriter, GGMLQuantizationType
from safetensors.numpy import load_file

MP_SILU_SCALE = 1.0 / 0.596
RES_BALANCE = 0.3
ATTN_BALANCE = 0.3
CHANNELS_PER_HEAD = 64
FOLD_VERSION = 1


def mp_norm(w: np.ndarray, gain: float = 1.0) -> np.ndarray:
    """Effective weight of MPConv.forward (eval mode)."""
    w = w.astype(np.float64)
    norm = 1e-4 + np.linalg.norm(w.ravel()) * math.sqrt(1.0 / w.size)
    w = w / norm
    fan_in = w[0].size
    return w * (gain / math.sqrt(fan_in))


def mp_sum_coeffs(t: float) -> tuple[float, float]:
    """mp_sum([a, b], w=t) == a * ca + b * cb."""
    n = math.sqrt((1 - t) ** 2 + t ** 2)
    return (1 - t) / n, t / n


class ModelConverter:
    def __init__(self, name: str, cfg: dict, sd: dict, writer: GGUFWriter, dtype: GGMLQuantizationType):
        self.name = name
        self.cfg = cfg
        self.sd = sd
        self.w = writer
        self.dtype = dtype
        self.used = set()

    def get(self, key: str) -> np.ndarray:
        self.used.add(key)
        return self.sd[key]

    def scalar(self, key: str) -> float:
        return float(self.get(key).reshape(()))

    def add(self, key: str, arr: np.ndarray, big: bool = True):
        arr = np.ascontiguousarray(arr)
        if big and self.dtype == GGMLQuantizationType.F16:
            self.w.add_tensor(f"{self.name}.{key}", arr.astype(np.float16), raw_dtype=GGMLQuantizationType.F16)
        else:
            self.w.add_tensor(f"{self.name}.{key}", arr.astype(np.float32), raw_dtype=GGMLQuantizationType.F32)

    def convert(self):
        cfg = self.cfg
        mults = cfg["model_channel_mults"]
        mc = cfg["model_channels"]
        lpb = cfg["layers_per_block"]
        image_size = cfg["image_size"]
        attn_res = cfg.get("attn_resolutions") or []
        emb_channels = cfg.get("emb_channels") or mc * max(mults)
        noise_dims = mc if cfg.get("noise_emb_dims") is None else cfg["noise_emb_dims"]
        assert cfg.get("fourier_scale") == "pos", "only positional noise embedding is supported"
        assert not cfg.get("block_kwargs"), "custom block kwargs are not supported"
        assert not cfg.get("encode_only")

        p = f"fd.{self.name}."
        self.w.add_uint32(p + "in_channels", cfg["in_channels"])
        self.w.add_uint32(p + "out_channels", cfg["out_channels"])
        self.w.add_uint32(p + "model_channels", mc)
        self.w.add_array(p + "channel_mults", mults)
        self.w.add_uint32(p + "layers_per_block", lpb)
        self.w.add_uint32(p + "emb_channels", emb_channels)
        self.w.add_uint32(p + "noise_emb_dims", noise_dims)
        self.w.add_float32(p + "concat_balance", cfg["concat_balance"])

        # --- embedding layers (kept in f32, evaluated on the CPU) ---------------
        self.add("noise_fourier.freqs", self.get("noise_fourier.freqs"), big=False)
        self.add("noise_linear.weight", mp_norm(self.get("noise_linear.weight")), big=False)
        cond = cfg.get("conditional_inputs") or []
        self.w.add_uint32(p + "n_cond", len(cond))
        cond_weights = [1.0]
        for i, (kind, dim, weight) in enumerate(cond):
            self.w.add_string(p + f"cond.{i}.type", kind)
            self.w.add_uint32(p + f"cond.{i}.dim", dim)
            cond_weights.append(weight)
            if kind == "float":
                self.add(f"cond.{i}.freqs", self.get(f"conditional_layers.{i}.0.freqs"), big=False)
                self.add(f"cond.{i}.phases", self.get(f"conditional_layers.{i}.0.phases"), big=False)
                self.add(f"cond.{i}.weight", mp_norm(self.get(f"conditional_layers.{i}.1.weight")), big=False)
            elif kind == "tensor":
                self.add(f"cond.{i}.weight", mp_norm(self.get(f"conditional_layers.{i}.weight")), big=False)
            else:
                raise ValueError(f"unsupported conditional input type {kind}")
        self.w.add_array(p + "cond_weights", cond_weights)

        # --- encoder ------------------------------------------------------------
        enc_names = []
        skips = []
        cout = cfg["in_channels"] + 1
        for level, ch in enumerate(mc * m for m in mults):
            res = image_size // 2 ** level
            if level == 0:
                name = f"enc.{res}x{res}_conv"
                self.add(name + ".weight", mp_norm(self.get(name + ".weight")))
                enc_names.append(("conv", name, cout, ch, False))
                cout = ch
            else:
                name = f"enc.{res}x{res}_down"
                self.block(name, cout, cout, "enc", res in attn_res)
                enc_names.append(("down", name, cout, cout, res in attn_res))
            skips.append(cout)
            for idx in range(lpb):
                cin, cout = cout, ch
                name = f"enc.{res}x{res}_block{idx}"
                self.block(name, cin, cout, "enc", res in attn_res)
                enc_names.append(("block", name, cin, cout, res in attn_res))
                skips.append(cout)

        # --- decoder ------------------------------------------------------------
        dec_names = []
        for level, ch in reversed(list(enumerate(mc * m for m in mults))):
            res = image_size // 2 ** level
            if level == len(mults) - 1:
                for j, attn in ((0, cfg["midblock_attention"]), (1, False)):
                    name = f"dec.{res}x{res}_in{j}"
                    self.block(name, cout, cout, "dec", attn)
                    dec_names.append(("in", name, cout, cout, attn))
            else:
                name = f"dec.{res}x{res}_up"
                self.block(name, cout, cout, "dec", False)
                dec_names.append(("up", name, cout, cout, False))
            for idx in range(lpb + 1):
                skip_ch = skips.pop()
                cin, cout = cout + skip_ch, ch
                name = f"dec.{res}x{res}_block{idx}"
                attn = res in attn_res
                self.block(name, cin, cout, "dec", attn)
                dec_names.append(("block", name, cin, cout, attn))
        assert not skips

        out_gain = self.scalar("out_gain") if "out_gain" in self.sd else 1.0
        self.add("out_conv.weight", mp_norm(self.get("out_conv.weight"), gain=out_gain))

        # Layer list for the runtime: "kind:name:cin:cout:attn" (decoder blocks consume skips in order)
        layers = [f"{k}:{n}:{a}:{b}:{int(t)}" for k, n, a, b, t in enc_names + dec_names]
        self.w.add_array(p + "layers", layers)

        unused = set(self.sd) - self.used - {k for k in self.sd if k.startswith("logvar_")}
        assert not unused, f"unconverted tensors in {self.name}: {sorted(unused)}"

    def block(self, name: str, cin: int, cout: int, mode: str, attention: bool):
        _, res_b = mp_sum_coeffs(RES_BALANCE)
        emb_gain = self.scalar(name + ".emb_gain")
        res0_in = cout if mode == "enc" else cin
        w0 = self.get(name + ".conv_res0.weight")
        assert w0.shape[1] == res0_in, (name, w0.shape, res0_in)
        self.add(name + ".conv_res0.weight", mp_norm(w0) * MP_SILU_SCALE)
        self.add(name + ".conv_res1.weight", mp_norm(self.get(name + ".conv_res1.weight")) * (MP_SILU_SCALE * res_b))
        # emb_linear consumes mp_silu(emb); the runtime passes silu(emb).
        self.add(name + ".emb_linear.weight", mp_norm(self.get(name + ".emb_linear.weight"), gain=emb_gain) * MP_SILU_SCALE)
        if cin != cout:
            ws = self.get(name + ".conv_skip.weight")
            self.add(name + ".conv_skip.weight", mp_norm(ws))
        if attention:
            heads = cout // CHANNELS_PER_HEAD
            ch = cout // heads
            qkv = mp_norm(self.get(name + ".attn_qkv.weight"))  # [3C, C, 1, 1], index h*ch*3 + c*3 + k
            order = [h * ch * 3 + c * 3 + k for k in range(3) for h in range(heads) for c in range(ch)]
            self.add(name + ".attn_qkv.weight", qkv[order])
            _, attn_b = mp_sum_coeffs(ATTN_BALANCE)
            self.add(name + ".attn_proj.weight", mp_norm(self.get(name + ".attn_proj.weight")) * attn_b)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("weights_dir", type=Path)
    ap.add_argument("pipeline_data", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--dtype", choices=["f16", "f32"], default="f16")
    args = ap.parse_args()

    dtype = GGMLQuantizationType.F16 if args.dtype == "f16" else GGMLQuantizationType.F32
    writer = GGUFWriter(str(args.out), "terrain-diffusion")
    writer.add_name("terrain-diffusion-30m")
    writer.add_uint32("fd.fold_version", FOLD_VERSION)

    pipe_cfg = json.loads((args.weights_dir / "config.json").read_text())
    for key in ("coarse_means", "coarse_stds", "cond_snr", "frequency_mult"):
        writer.add_array(f"fd.pipeline.{key}", [float(v) for v in pipe_cfg[key]])
    for key in ("residual_mean", "residual_std", "native_resolution"):
        writer.add_float32(f"fd.pipeline.{key}", float(pipe_cfg[key]))
    writer.add_uint32("fd.pipeline.latent_compression", int(pipe_cfg["latent_compression"]))
    assert int(pipe_cfg["coarse_pooling"]) == 1

    pdata = json.loads(args.pipeline_data.read_text())
    writer.add_uint32("fd.synth.n_quantiles", int(pdata["n_quantiles"]))
    for key in ("a_temp_std", "b_temp_std", "temp_std_p1", "temp_std_p99"):
        writer.add_float32(f"fd.synth.{key}", float(pdata[key]))
    writer.add_tensor("synth.data_quantiles", np.asarray(pdata["data_quantile_tables"], dtype=np.float32))

    for name in ("coarse", "base", "decoder"):
        folder = args.weights_dir / f"{name}_model"
        cfg = json.loads((folder / "config.json").read_text())
        sd = load_file(str(folder / "diffusion_pytorch_model.safetensors"))
        ModelConverter(name, cfg, sd, writer, dtype).convert()
        print(f"converted {name}")

    writer.write_header_to_file()
    writer.write_kv_data_to_file()
    writer.write_tensors_to_file(progress=True)
    writer.close()
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
