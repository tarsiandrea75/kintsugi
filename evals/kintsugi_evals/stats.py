"""The protocol's statistics (ADR-015), standard library only.

Every function is deterministic: the bootstrap takes an explicit seed.
"""

import math
import random

# Two-sided 95% normal quantile.
Z95 = 1.959963984540054


def wilson(k, n, z=Z95):
    """Wilson score interval of k successes in n trials, as (low, high).

    Why Wilson and not k/n +- z*se: on small n and rates near 0 or 1 the normal
    interval leaves [0, 1] and is too narrow; Wilson stays inside and keeps its coverage.
    """
    if n <= 0:
        raise ValueError("wilson needs at least one trial")
    if not 0 <= k <= n:
        raise ValueError("k must lie between 0 and n")
    p = k / n
    z2 = z * z
    centre = (p + z2 / (2 * n)) / (1 + z2 / n)
    half = z * math.sqrt(p * (1 - p) / n + z2 / (4 * n * n)) / (1 + z2 / n)
    return max(0.0, centre - half), min(1.0, centre + half)


def mcnemar_exact(b, c):
    """Exact two-sided McNemar p-value from the discordant counts b and c.

    Under the null each discordant pair favours either side with probability 1/2,
    so the p-value is twice the binomial tail of min(b, c) in b + c trials, capped at 1.
    """
    if b < 0 or c < 0:
        raise ValueError("discordant counts cannot be negative")
    n = b + c
    if n == 0:
        return 1.0
    tail = sum(math.comb(n, i) for i in range(min(b, c) + 1)) / 2**n
    return min(1.0, 2 * tail)


def _case_means(cases):
    return [(sum(a) / len(a), sum(b) / len(b)) for a, b in cases.values()]


def cluster_bootstrap_diff(cases, seed, iterations=10000, level=0.95):
    """Paired difference of mean accuracy, second minus first, with a cluster bootstrap CI.

    cases maps a case key to (first, second): the 0/1 outcomes of that case's repetitions
    under each config. The case is the unit: each case is weighted once, whatever its number
    of repetitions, and resampling draws whole cases, so correlated repetitions do not
    shrink the interval. Returns (difference, low, high), percentile interval.
    """
    if not cases:
        raise ValueError("the bootstrap needs at least one case")
    diffs = [second - first for first, second in _case_means(cases)]
    n = len(diffs)
    point = sum(diffs) / n
    rng = random.Random(seed)
    stats = sorted(sum(rng.choices(diffs, k=n)) / n for _ in range(iterations))
    alpha = (1 - level) / 2
    low = stats[int(math.floor(alpha * (iterations - 1)))]
    high = stats[int(math.ceil((1 - alpha) * (iterations - 1)))]
    return point, low, high


def holm(pvalues):
    """Holm-Bonferroni adjusted p-values, in the input order.

    Why: testing H1-H3 together raises the chance that one of them wins by luck;
    Holm controls that family-wise error and is never weaker than Bonferroni.
    """
    m = len(pvalues)
    order = sorted(range(m), key=lambda i: pvalues[i])
    adjusted = [0.0] * m
    running = 0.0
    for rank, i in enumerate(order):
        running = max(running, (m - rank) * pvalues[i])
        adjusted[i] = min(1.0, running)
    return adjusted


def dev_keep(pairs, min_net_gain=3, max_class_loss=1):
    """The dev keep rule: keep a variant only with a net gain of at least 3 cases
    and no fault class losing more than 1, net.

    pairs is a list of (fault, baseline_correct, variant_correct) with 0/1 outcomes.
    """
    net = 0
    per_class = {}
    for fault, base, variant in pairs:
        delta = variant - base
        net += delta
        per_class[fault] = per_class.get(fault, 0) + delta
    return net >= min_net_gain and all(d >= -max_class_loss for d in per_class.values())
