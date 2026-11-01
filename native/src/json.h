// Minimal JSON reader for model metadata (configs, safetensors headers).
#pragma once

#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace fd {

class Json {
public:
    enum Type { Null, Bool, Number, String, Array, Object };

    static Json parse(const std::string& text);

    Type type() const { return type_; }
    bool is_null() const { return type_ == Null; }
    double num() const { return check(Number), num_; }
    int64_t i64() const { return (int64_t)num(); }
    bool boolean() const { return check(Bool), bool_; }
    const std::string& str() const { return check(String), str_; }
    const std::vector<Json>& arr() const { return check(Array), arr_; }
    const std::map<std::string, Json>& obj() const { return check(Object), obj_; }
    bool has(const std::string& k) const { return type_ == Object && obj_.count(k) != 0; }
    const Json& operator[](const std::string& k) const;
    const Json& operator[](size_t i) const { return arr().at(i); }

private:
    void check(Type t) const {
        if (type_ != t) throw std::runtime_error("json: unexpected value type");
    }
    Type type_ = Null;
    double num_ = 0;
    bool bool_ = false;
    std::string str_;
    std::vector<Json> arr_;
    std::map<std::string, Json> obj_;
    friend class JsonParser;
};

}  // namespace fd
