#!/usr/bin/env python3
"""Dump reference inputs/outputs of the upstream PyTorch models for native-engine tests.

Runs ``EDMUnet2D`` from the upstream terrain-diffusion repo in float32 on deterministic
random inputs and writes raw little-endian float32 files:

    <out>/<model>.x.bin      input  [N, C, H, W]
    <out>/<model>.t.bin      noise labels [N]
    <out>/<model>.cond<i>.bin conditional inputs
    <out>/<model>.y.bin      output [N, C_out, H, W]

Usage: make_reference.py <terrain-diffusion repo> <weights_dir> <out_dir>
"""
import json
import sys
from pathlib import Path

import numpy as np
import torch

CASES = {
    # model: (batch, tile size, cond spec)
    "coarse": (1, 64),
    "base": (2, 64),
    "decoder": (1, 256),
}


def main():
    repo, weights, out = map(Path, sys.argv[1:4])
    sys.path.insert(0, str(repo))
    from terrain_diffusion.models.edm_unet import EDMUnet2D

    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(1234)
    torch.set_grad_enabled(False)
    for name, (n, size) in CASES.items():
        model = EDMUnet2D.from_pretrained(str(weights / f"{name}_model")).eval().float()
        cfg = model.config
        x = rng.standard_normal((n, cfg.in_channels, size, size)).astype(np.float32)
        t = rng.uniform(0.1, 1.5, (n,)).astype(np.float32)
        conds = []
        for kind, dim, _ in cfg.conditional_inputs or []:
            if kind == "float":
                conds.append(rng.uniform(-2, 0, (n,)).astype(np.float32))
            else:
                conds.append(rng.standard_normal((n, dim)).astype(np.float32))
        y = model(torch.from_numpy(x), torch.from_numpy(t), [torch.from_numpy(c) for c in conds]).numpy()
        x.tofile(out / f"{name}.x.bin")
        t.tofile(out / f"{name}.t.bin")
        for i, c in enumerate(conds):
            c.tofile(out / f"{name}.cond{i}.bin")
        y.astype(np.float32).tofile(out / f"{name}.y.bin")
        meta = {"n": n, "size": size, "in": cfg.in_channels, "out": cfg.out_channels, "n_cond": len(conds)}
        (out / f"{name}.json").write_text(json.dumps(meta))
        print(name, x.shape, "->", y.shape, "std", float(y.std()))


if __name__ == "__main__":
    main()
