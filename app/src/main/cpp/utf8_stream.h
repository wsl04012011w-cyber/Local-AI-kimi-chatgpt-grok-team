// Conversor incremental UTF-8 -> UTF-16 para a fronteira JNI.
//
// Pedacos ("pieces") de tokens sao bytes crus: um caractere multibyte (CJK,
// emoji, acentos raros) pode ser dividido entre dois tokens, e sequencias de
// 4 bytes (emoji) NAO sao "Modified UTF-8" validas para NewStringUTF. Passar
// isso para NewStringUTF aborta a VM quando CheckJNI esta ligado (apps
// debuggable) e produz lixo nos demais casos. Aqui acumulamos bytes, so
// emitimos sequencias completas e convertemos para UTF-16 (usado com NewString).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace localai {

class Utf8Stream {
public:
    // Acrescenta bytes e devolve o UTF-16 dos caracteres ja completos.
    // Bytes invalidos viram U+FFFD. Uma sequencia incompleta no fim fica retida.
    std::u16string push(const char* data, size_t len) {
        pending_.append(data, len);
        return drain(false);
    }
    std::u16string push(const std::string& s) { return push(s.data(), s.size()); }

    // Fim da geracao: o que sobrou (sequencia truncada) vira U+FFFD.
    std::u16string finish() { return drain(true); }

    size_t pendingBytes() const { return pending_.size(); }
    void reset() { pending_.clear(); }

private:
    static constexpr char16_t kReplacement = 0xFFFD;

    // Comprimento esperado da sequencia a partir do byte lider; 0 = lider invalido.
    static int expectedLen(uint8_t b) {
        if (b < 0x80) return 1;
        if (b >= 0xC2 && b <= 0xDF) return 2;
        if (b >= 0xE0 && b <= 0xEF) return 3;
        if (b >= 0xF0 && b <= 0xF4) return 4;
        return 0;
    }
    static bool isCont(uint8_t b) { return (b & 0xC0) == 0x80; }

    std::u16string drain(bool final_) {
        std::u16string out;
        size_t i = 0;
        const size_t n = pending_.size();
        while (i < n) {
            const uint8_t b0 = (uint8_t) pending_[i];
            const int need = expectedLen(b0);
            if (need == 0) { out.push_back(kReplacement); i++; continue; }
            if (need == 1) { out.push_back((char16_t) b0); i++; continue; }

            // Quantos bytes de continuacao validos existem a partir de i+1?
            int have = 1;
            while (have < need && i + have < n && isCont((uint8_t) pending_[i + have])) have++;

            if (have < need) {
                if (i + have >= n && !final_) break;       // truncada no fim: espera mais bytes
                out.push_back(kReplacement);                // sequencia quebrada
                i += have;
                continue;
            }

            uint32_t cp = 0;
            if (need == 2) {
                cp = ((b0 & 0x1F) << 6) | ((uint8_t) pending_[i + 1] & 0x3F);
            } else if (need == 3) {
                cp = ((b0 & 0x0F) << 12) | (((uint8_t) pending_[i + 1] & 0x3F) << 6)
                   | ((uint8_t) pending_[i + 2] & 0x3F);
                // overlong (< U+0800) ou surrogate UTF-16 codificado (U+D800..DFFF)
                if (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF)) {
                    out.push_back(kReplacement); i += need; continue;
                }
            } else {
                cp = ((b0 & 0x07) << 18) | (((uint8_t) pending_[i + 1] & 0x3F) << 12)
                   | (((uint8_t) pending_[i + 2] & 0x3F) << 6) | ((uint8_t) pending_[i + 3] & 0x3F);
                if (cp < 0x10000 || cp > 0x10FFFF) {
                    out.push_back(kReplacement); i += need; continue;
                }
            }

            if (cp >= 0x10000) {
                cp -= 0x10000;
                out.push_back((char16_t) (0xD800 + (cp >> 10)));
                out.push_back((char16_t) (0xDC00 + (cp & 0x3FF)));
            } else {
                out.push_back((char16_t) cp);
            }
            i += need;
        }
        pending_.erase(0, i);
        if (final_) pending_.clear();
        return out;
    }

    std::string pending_;
};

// UTF-16 (como vem do JNI via GetStringChars) -> UTF-8 padrao.
// GetStringUTFChars devolve "Modified UTF-8" (emoji vira 2x 3 bytes, CESU-8), que o
// tokenizer do llama.cpp nao entende; por isso o prompt e convertido aqui.
inline std::string utf16_to_utf8(const char16_t* s, size_t n) {
    std::string out;
    out.reserve(n + n / 2);
    for (size_t i = 0; i < n; i++) {
        uint32_t cp = s[i];
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (i + 1 < n && s[i + 1] >= 0xDC00 && s[i + 1] <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (s[i + 1] - 0xDC00);
                i++;
            } else cp = 0xFFFD;                       // surrogate alto solto
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            cp = 0xFFFD;                              // surrogate baixo solto
        }
        if (cp < 0x80) out.push_back((char) cp);
        else if (cp < 0x800) { out.push_back((char) (0xC0 | (cp >> 6))); out.push_back((char) (0x80 | (cp & 0x3F))); }
        else if (cp < 0x10000) { out.push_back((char) (0xE0 | (cp >> 12))); out.push_back((char) (0x80 | ((cp >> 6) & 0x3F))); out.push_back((char) (0x80 | (cp & 0x3F))); }
        else { out.push_back((char) (0xF0 | (cp >> 18))); out.push_back((char) (0x80 | ((cp >> 12) & 0x3F))); out.push_back((char) (0x80 | ((cp >> 6) & 0x3F))); out.push_back((char) (0x80 | (cp & 0x3F))); }
    }
    return out;
}

} // namespace localai
