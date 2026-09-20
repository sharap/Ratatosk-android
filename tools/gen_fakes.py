#!/usr/bin/env python3
"""Пересобирает заглушки ядра для проверок.

Биндинги `ratatosk_ffi.kt` пересобираются при каждой сборке, и стоит ядру
обзавестись новым методом, как `FakeClient` перестаёт компилироваться.
Чинится это не руками: запустить `python3 tools/gen_fakes.py`.

Заглушки — ровно интерфейсы uniffi, где каждый метод падает с собственным
именем: проверка объявляет только то, что ей нужно, а всё лишнее само
скажет об этом в отчёте.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BINDINGS = ROOT / "app/src/main/java/org/ratatosk/core/ratatosk_ffi.kt"
FAKES = ROOT / "app/src/test/java/chat/ratatosk/android/fake/Fakes.kt"
TAIL_MARK = "/**\n * Подставное ядро."

HEAD = """package chat.ratatosk.android.fake

import org.ratatosk.core.*

/** Модель позвала то, чего проверка не ждала, — это и есть находка. */
internal fun notCalled(name: String): Nothing =
    throw AssertionError("ядро позвали там, где не ждали: $name")
"""


def interface(src: str, name: str) -> str:
    start = src.index(f"public interface {name} {{")
    return src[start:src.index("\n}\n", start)]


def stub(src: str, iface: str, cls: str) -> str:
    body = []
    for line in interface(src, iface).split("\n"):
        m = re.match(r"^    fun (`\w+`)\((.*)\)(: (.+))?$", line.rstrip())
        if not m:
            continue
        name, args, _, ret = m.groups()
        body.append(
            f"    override fun {name}({args}): {ret or 'Unit'} = notCalled(\"{name.strip('`')}\")"
        )
    return f"""
/**
 * Заглушка [{iface}]: каждый вызов падает с именем метода.
 *
 * Проверка объявляет только то, что ей нужно, — а всё, чего модель звать
 * не должна была, само скажет об этом в отчёте, вместо `NullPointerException`
 * где-то в середине.
 *
 * Собрано `tools/gen_fakes.py` — руками не править.
 */
open class {cls} : {iface} {{
{chr(10).join(body)}
}}
"""


def main() -> int:
    src = BINDINGS.read_text()
    old = FAKES.read_text()
    tail = old[old.index(TAIL_MARK):]
    FAKES.write_text(
        HEAD
        + stub(src, "RatatoskClientInterface", "FakeClient")
        + stub(src, "RatatoskCompanionInterface", "FakeCompanion")
        + "\n"
        + tail
    )
    print(f"заглушки пересобраны: {FAKES.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
