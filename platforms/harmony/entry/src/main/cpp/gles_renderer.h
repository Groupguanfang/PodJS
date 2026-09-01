#pragma once
#include <cstddef>
#include <cstdint>
class GlesRenderer { public: bool validate(const uint32_t*, size_t) const; bool submit(const uint32_t*, size_t, uint64_t); private: uint64_t hash_=0; };
