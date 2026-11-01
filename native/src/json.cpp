#include "json.h"

#include <cmath>
#include <cstdlib>
#include <cstring>

namespace fd {

class JsonParser {
public:
    explicit JsonParser(const std::string& s) : s_(s) {}

    Json value() {
        ws();
        if (p_ >= s_.size()) fail("unexpected end");
        const char c = s_[p_];
        Json j;
        if (c == '{') {
            j.type_ = Json::Object;
            p_++;
            ws();
            if (peek('}')) return p_++, j;
            for (;;) {
                ws();
                std::string k = string();
                ws();
                expect(':');
                j.obj_.emplace(std::move(k), value());
                ws();
                if (peek(',')) {
                    p_++;
                    continue;
                }
                expect('}');
                return j;
            }
        }
        if (c == '[') {
            j.type_ = Json::Array;
            p_++;
            ws();
            if (peek(']')) return p_++, j;
            for (;;) {
                j.arr_.push_back(value());
                ws();
                if (peek(',')) {
                    p_++;
                    continue;
                }
                expect(']');
                return j;
            }
        }
        if (c == '"') {
            j.type_ = Json::String;
            j.str_ = string();
            return j;
        }
        if (s_.compare(p_, 4, "true") == 0) return p_ += 4, j.type_ = Json::Bool, j.bool_ = true, j;
        if (s_.compare(p_, 5, "false") == 0) return p_ += 5, j.type_ = Json::Bool, j;
        if (s_.compare(p_, 4, "null") == 0) return p_ += 4, j;
        if (s_.compare(p_, 3, "NaN") == 0) return p_ += 3, j.type_ = Json::Number, j.num_ = NAN, j;
        char* end = nullptr;
        j.num_ = std::strtod(s_.c_str() + p_, &end);
        if (end == s_.c_str() + p_) fail("bad token");
        p_ = end - s_.c_str();
        j.type_ = Json::Number;
        return j;
    }

    void finish() {
        ws();
        if (p_ != s_.size()) fail("trailing data");
    }

private:
    void ws() {
        while (p_ < s_.size() && std::strchr(" \t\r\n", s_[p_])) p_++;
    }
    bool peek(char c) const { return p_ < s_.size() && s_[p_] == c; }
    void expect(char c) {
        if (!peek(c)) fail(std::string("expected '") + c + "'");
        p_++;
    }
    [[noreturn]] void fail(const std::string& why) const {
        throw std::runtime_error("json parse error at " + std::to_string(p_) + ": " + why);
    }
    std::string string() {
        expect('"');
        std::string out;
        while (p_ < s_.size() && s_[p_] != '"') {
            char c = s_[p_++];
            if (c != '\\') {
                out += c;
                continue;
            }
            if (p_ >= s_.size()) fail("bad escape");
            c = s_[p_++];
            switch (c) {
                case 'n': out += '\n'; break;
                case 't': out += '\t'; break;
                case 'r': out += '\r'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'u': {
                    const unsigned cp = (unsigned)std::strtoul(s_.substr(p_, 4).c_str(), nullptr, 16);
                    p_ += 4;
                    if (cp < 0x80) out += (char)cp;
                    else if (cp < 0x800) out += (char)(0xC0 | (cp >> 6)), out += (char)(0x80 | (cp & 0x3F));
                    else out += (char)(0xE0 | (cp >> 12)), out += (char)(0x80 | ((cp >> 6) & 0x3F)), out += (char)(0x80 | (cp & 0x3F));
                    break;
                }
                default: out += c;
            }
        }
        expect('"');
        return out;
    }

    const std::string& s_;
    size_t p_ = 0;
};

Json Json::parse(const std::string& text) {
    JsonParser p(text);
    Json j = p.value();
    p.finish();
    return j;
}

const Json& Json::operator[](const std::string& k) const {
    auto it = obj().find(k);
    if (it == obj_.end()) throw std::runtime_error("json: missing key '" + k + "'");
    return it->second;
}

}  // namespace fd
