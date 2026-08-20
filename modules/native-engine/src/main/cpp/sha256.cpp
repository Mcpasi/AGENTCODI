#include "sha256.h"

#include <array>
#include <cstdint>
#include <iomanip>
#include <sstream>

namespace agentcodi {
namespace {

constexpr std::array<std::uint32_t, 64> kRoundConstants {{
    0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
    0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
    0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
    0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
    0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
    0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
    0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
    0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
    0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
    0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
    0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
    0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
    0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
    0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
    0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
    0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
}};

std::uint32_t rotate_right(std::uint32_t value, unsigned int count) {
  return (value >> count) | (value << (32U - count));
}

class Sha256State final {
 public:
  void Update(const unsigned char* bytes, std::size_t size) {
    if (bytes == nullptr || size == 0U) {
      return;
    }
    total_bytes_ += static_cast<std::uint64_t>(size);
    std::size_t offset = 0U;
    while (offset < size) {
      const std::size_t available = block_.size() - block_size_;
      const std::size_t count = size - offset < available
          ? size - offset
          : available;
      for (std::size_t index = 0U; index < count; ++index) {
        block_[block_size_ + index] = bytes[offset + index];
      }
      block_size_ += count;
      offset += count;
      if (block_size_ == block_.size()) {
        Transform(block_.data());
        block_size_ = 0U;
      }
    }
  }

  std::array<unsigned char, 32> Finish() {
    const std::uint64_t total_bits = total_bytes_ * 8U;
    block_[block_size_++] = 0x80U;
    if (block_size_ > 56U) {
      while (block_size_ < block_.size()) {
        block_[block_size_++] = 0U;
      }
      Transform(block_.data());
      block_size_ = 0U;
    }
    while (block_size_ < 56U) {
      block_[block_size_++] = 0U;
    }
    for (unsigned int index = 0U; index < 8U; ++index) {
      block_[56U + index] = static_cast<unsigned char>(
          total_bits >> (56U - index * 8U));
    }
    Transform(block_.data());

    std::array<unsigned char, 32> digest {};
    for (std::size_t word = 0U; word < state_.size(); ++word) {
      digest[word * 4U] = static_cast<unsigned char>(state_[word] >> 24U);
      digest[word * 4U + 1U] = static_cast<unsigned char>(state_[word] >> 16U);
      digest[word * 4U + 2U] = static_cast<unsigned char>(state_[word] >> 8U);
      digest[word * 4U + 3U] = static_cast<unsigned char>(state_[word]);
    }
    return digest;
  }

 private:
  void Transform(const unsigned char* block) {
    std::array<std::uint32_t, 64> words {};
    for (std::size_t index = 0U; index < 16U; ++index) {
      const std::size_t offset = index * 4U;
      words[index] = (static_cast<std::uint32_t>(block[offset]) << 24U)
          | (static_cast<std::uint32_t>(block[offset + 1U]) << 16U)
          | (static_cast<std::uint32_t>(block[offset + 2U]) << 8U)
          | static_cast<std::uint32_t>(block[offset + 3U]);
    }
    for (std::size_t index = 16U; index < words.size(); ++index) {
      const std::uint32_t first = words[index - 15U];
      const std::uint32_t second = words[index - 2U];
      const std::uint32_t sigma_zero = rotate_right(first, 7U)
          ^ rotate_right(first, 18U) ^ (first >> 3U);
      const std::uint32_t sigma_one = rotate_right(second, 17U)
          ^ rotate_right(second, 19U) ^ (second >> 10U);
      words[index] = words[index - 16U] + sigma_zero
          + words[index - 7U] + sigma_one;
    }

    std::uint32_t a = state_[0];
    std::uint32_t b = state_[1];
    std::uint32_t c = state_[2];
    std::uint32_t d = state_[3];
    std::uint32_t e = state_[4];
    std::uint32_t f = state_[5];
    std::uint32_t g = state_[6];
    std::uint32_t h = state_[7];
    for (std::size_t index = 0U; index < words.size(); ++index) {
      const std::uint32_t choice = (e & f) ^ ((~e) & g);
      const std::uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
      const std::uint32_t sum_zero = rotate_right(a, 2U)
          ^ rotate_right(a, 13U) ^ rotate_right(a, 22U);
      const std::uint32_t sum_one = rotate_right(e, 6U)
          ^ rotate_right(e, 11U) ^ rotate_right(e, 25U);
      const std::uint32_t first = h + sum_one + choice
          + kRoundConstants[index] + words[index];
      const std::uint32_t second = sum_zero + majority;
      h = g;
      g = f;
      f = e;
      e = d + first;
      d = c;
      c = b;
      b = a;
      a = first + second;
    }
    state_[0] += a;
    state_[1] += b;
    state_[2] += c;
    state_[3] += d;
    state_[4] += e;
    state_[5] += f;
    state_[6] += g;
    state_[7] += h;
  }

  std::array<std::uint32_t, 8> state_ {{
      0x6a09e667U,
      0xbb67ae85U,
      0x3c6ef372U,
      0xa54ff53aU,
      0x510e527fU,
      0x9b05688cU,
      0x1f83d9abU,
      0x5be0cd19U,
  }};
  std::array<unsigned char, 64> block_ {};
  std::size_t block_size_ = 0U;
  std::uint64_t total_bytes_ = 0U;
};

}  // namespace

std::string Sha256Hex(const unsigned char* bytes, std::size_t size) {
  Sha256State state;
  state.Update(bytes, size);
  const std::array<unsigned char, 32> digest = state.Finish();
  std::ostringstream encoded;
  encoded << std::hex << std::setfill('0');
  for (unsigned char value : digest) {
    encoded << std::setw(2) << static_cast<unsigned int>(value);
  }
  return encoded.str();
}

}  // namespace agentcodi
