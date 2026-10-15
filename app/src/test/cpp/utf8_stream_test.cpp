// Teste de host (sem Android/llama.cpp):
//   g++ -std=c++17 -Wall -Wextra -I app/src/main/cpp app/src/test/cpp/utf8_stream_test.cpp -o /tmp/utf8_test && /tmp/utf8_test
#include <cstdio>
#include <cstdlib>
#include <string>
#include "utf8_stream.h"

static int g_fail = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FALHOU %s:%d: %s\n", __FILE__, __LINE__, #cond); g_fail++; } } while (0)

using localai::Utf8Stream;

static std::u16string feedByByte(const std::string& s) {
    Utf8Stream st; std::u16string out;
    for (char c : s) out += st.push(&c, 1);
    out += st.finish();
    return out;
}

int main() {
    // ASCII e acento (2 bytes) inteiros
    { Utf8Stream st; CHECK(st.push("ol\xC3\xA1") == u"ol\u00E1"); CHECK(st.pendingBytes() == 0); }

    // "a" (2 bytes) dividido entre dois chunks: nada emitido ate completar
    { Utf8Stream st; CHECK(st.push("\xC3").empty()); CHECK(st.pendingBytes() == 1);
      CHECK(st.push("\xA3") == u"\u00E3"); CHECK(st.pendingBytes() == 0); }

    // CJK (3 bytes) byte a byte
    CHECK(feedByByte("\xE4\xBD\xA0\xE5\xA5\xBD") == u"\u4F60\u597D");

    // Emoji 4 bytes (U+1F600) -> par surrogate, byte a byte e dividido ao meio
    CHECK(feedByByte("\xF0\x9F\x98\x80") == u"\xD83D\xDE00");
    { Utf8Stream st; CHECK(st.push("\xF0\x9F").empty()); CHECK(st.push("\x98\x80") == u"\xD83D\xDE00"); }

    // Texto misto, byte a byte == tudo de uma vez
    { std::string mix = "Ok, vou pensar: \xE4\xBD\xA0 \xF0\x9F\x98\x80 fim\n";
      Utf8Stream a; std::u16string whole = a.push(mix) + a.finish();
      CHECK(feedByByte(mix) == whole); }

    // Byte de continuacao solto e lider invalido -> U+FFFD, sem travar
    { Utf8Stream st; CHECK(st.push("a\x80" "b") == u"a\uFFFDb"); }
    { Utf8Stream st; CHECK(st.push("\xFF" "x") == u"\uFFFDx"); }

    // Sequencia truncada no fim da geracao -> U+FFFD no finish()
    { Utf8Stream st; CHECK(st.push("ok\xE4\xBD") == u"ok"); CHECK(st.finish() == u"\uFFFD"); CHECK(st.pendingBytes() == 0); }

    // Lider seguido de byte nao-continuacao: lider vira U+FFFD, o proximo byte e preservado
    { Utf8Stream st; CHECK(st.push("\xE4" "A") == u"\uFFFDA"); }

    // Overlong (C0 80), surrogate codificado (ED A0 80) e > U+10FFFF (F4 90 80 80) rejeitados
    { Utf8Stream st; CHECK(st.push("\xC0\x80") == u"\uFFFD\uFFFD"); }
    { Utf8Stream st; CHECK(st.push("\xED\xA0\x80") == u"\uFFFD"); }
    { Utf8Stream st; CHECK(st.push("\xF4\x90\x80\x80") == u"\uFFFD"); }

    // Conteudo longo (200k bytes ASCII) em pedacos de 7 bytes
    { Utf8Stream st; std::u16string out; std::string big(200000, 'x');
      for (size_t i = 0; i < big.size(); i += 7) out += st.push(big.data() + i, std::min<size_t>(7, big.size() - i));
      out += st.finish(); CHECK(out.size() == big.size()); }

    // NUL embutido nao trunca (NewString usa comprimento, nao c_str)
    { Utf8Stream st; std::string s("a\0b", 3); CHECK(st.push(s) == std::u16string(u"a\0b", 3)); }

    // utf16_to_utf8: ASCII, acento, CJK, emoji (par surrogate -> 4 bytes UTF-8 padrao, nao CESU-8)
    using localai::utf16_to_utf8;
    CHECK(utf16_to_utf8(u"ol\u00E1", 3) == "ol\xC3\xA1");
    CHECK(utf16_to_utf8(u"\u4F60", 1) == "\xE4\xBD\xA0");
    CHECK(utf16_to_utf8(u"\xD83D\xDE00", 2) == "\xF0\x9F\x98\x80");
    // surrogates soltos viram U+FFFD (EF BF BD)
    CHECK(utf16_to_utf8(u"\xD83D" u"a", 2) == "\xEF\xBF\xBD" "a");
    CHECK(utf16_to_utf8(u"\xDE00", 1) == "\xEF\xBF\xBD");
    // ida e volta
    { std::u16string src = u"Qwen \u4F60\u597D \xD83D\xDE00 pensa\u00E7\u00E3o";
      Utf8Stream st; std::u16string back = st.push(utf16_to_utf8(src.data(), src.size())) + st.finish();
      CHECK(back == src); }

    if (g_fail == 0) std::printf("OK: todos os testes de Utf8Stream passaram\n");
    return g_fail == 0 ? 0 : 1;
}
