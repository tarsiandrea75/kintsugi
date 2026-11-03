#!/usr/bin/env python3
"""Turns holdout-2's seed into its case schedule (ADR-026, odd/tasks/lab-realism-min.md).

    infra/scripts/holdout2-schedule.py SEED_FILE [--out PATH] [--f1 N] [--f2 N] [--f3 N] [--healthy N]
    infra/scripts/holdout2-schedule.py --seed-file SEED_FILE [...]

The seed file holds 64 hex characters (256 bits from /dev/urandom), surrounding whitespace ignored. The output is a TSV
with the header `case, class, parameter, value`: one row per case, numbered from 1 in the drawn order.

Every draw comes from SHA-256, never from Python's random module, whose shuffle and choice no Python version promises to
keep (ADR-026, amendment). So the same seed gives the same file byte for byte, on any interpreter:
- The stream: block i (i = 0, 1, 2, ...) is SHA-256(the seed's 32 bytes || i as 8 bytes big-endian); each block gives
  four words, its bytes 0-7, 8-15, 16-23 and 24-31 read as unsigned big-endian integers, consumed in that order.
- below(n), an unbiased integer in [0, n): take the next word w; if w is at or above the largest multiple of n not over
  2^64, discard it and take the next one; else return w mod n.
- The order of the draws:
  1. the class list (the F1 cases, then F2, F3 and healthy, as many as the counts) is shuffled once by Fisher-Yates,
     for position k from the last down to 1: swap k with below(k + 1);
  2. then, case by case in the shuffled order, an F1 case draws its latency and an F2 case its status, each as
     values[below(len(values))] over the values below; F3 and healthy cases draw nothing.

Exits 2 on a missing seed file, a malformed seed or a wrong argument. Python 3.11 or later, standard library only.
"""

import argparse
import hashlib
import re
import sys

# The classes, their default counts (75 cases) and their parameters, declared once (lab-realism-min.md, Design).
# A class with a tuple of values draws one per case; a single value is fixed; "-" means no parameter.
CLASSES = {
    "F1": {"count": 20, "parameter": "gateway_latency_ms", "values": (2000, 5000, 10000, 20000)},
    "F2": {"count": 20, "parameter": "gateway_error_status", "values": (429, 500, 503)},
    "F3": {"count": 20, "parameter": "wrong_port", "fixed": 9999},
    "healthy": {"count": 15, "parameter": "-", "fixed": "-"},
}
SEED = re.compile(r"[0-9a-fA-F]{64}")


WORD_SPACE = 1 << 64


class Stream:
    """The seed's stream of 64-bit words, and unbiased draws from it (the module docstring states the stream)."""

    def __init__(self, seed_bytes):
        self.seed, self.block, self.words = seed_bytes, 0, []

    def word(self):
        if not self.words:
            digest = hashlib.sha256(self.seed + self.block.to_bytes(8, "big")).digest()
            self.words = [int.from_bytes(digest[i:i + 8], "big") for i in range(0, 32, 8)]
            self.block += 1
        return self.words.pop(0)

    def below(self, n):
        """An unbiased integer in [0, n), by rejection of the words at or above the largest multiple of n."""
        limit = WORD_SPACE - WORD_SPACE % n
        while True:
            w = self.word()
            if w < limit:
                return w % n

    def shuffle(self, items):
        """Fisher-Yates, in place: for k from the last position down to 1, swap k with below(k + 1)."""
        for k in range(len(items) - 1, 0, -1):
            j = self.below(k + 1)
            items[k], items[j] = items[j], items[k]

    def choice(self, values):
        return values[self.below(len(values))]


def schedule(seed_hex, counts):
    """The schedule's TSV text for the seed and the counts per class."""
    stream = Stream(bytes.fromhex(seed_hex))
    classes = [name for name in CLASSES for _ in range(counts[name])]
    stream.shuffle(classes)
    lines = ["case\tclass\tparameter\tvalue"]
    for case, name in enumerate(classes, 1):
        spec = CLASSES[name]
        value = stream.choice(spec["values"]) if "values" in spec else spec["fixed"]
        lines.append("%d\t%s\t%s\t%s" % (case, name, spec["parameter"], value))
    return "\n".join(lines) + "\n"


def count(text):
    value = int(text)
    if value < 0:
        raise argparse.ArgumentTypeError("a count cannot be negative: %s" % text)
    return value


def main(argv):
    parser = argparse.ArgumentParser(description="Turns holdout-2's seed into its case schedule (ADR-026).")
    parser.add_argument("seed", nargs="?", help="the seed file (64 hex characters)")
    parser.add_argument("--seed-file", help="the seed file, as an option")
    parser.add_argument("--out", help="write the schedule here instead of stdout")
    for name, spec in CLASSES.items():
        parser.add_argument("--" + name.lower(), type=count, default=spec["count"],
                            help="%s cases (default %d)" % (name, spec["count"]))
    args = parser.parse_args(argv)
    path = args.seed_file or args.seed
    if not path or (args.seed_file and args.seed):
        print("Give one seed file, as an argument or with --seed-file; see --help", file=sys.stderr)
        return 2
    try:
        with open(path, encoding="ascii", errors="replace") as file:
            seed_hex = file.read().strip()
    except FileNotFoundError:
        print("No seed file: %s" % path, file=sys.stderr)
        return 2
    except OSError as error:
        print("Cannot read the seed file %s: %s" % (path, error.strerror), file=sys.stderr)
        return 2
    if not SEED.fullmatch(seed_hex):
        print("%s does not hold a seed of 64 hex characters" % path, file=sys.stderr)
        return 2
    text = schedule(seed_hex, {name: getattr(args, name.lower()) for name in CLASSES})
    if args.out:
        with open(args.out, "w", encoding="ascii", newline="\n") as file:
            file.write(text)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
