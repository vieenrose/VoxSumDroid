// CPU cluster discovery + affinity policy, shared by every native engine in this app.
//
// WHY THIS EXISTS
// ---------------
// A heterogeneous (big.LITTLE) thread pool barriers on its slowest worker, so a pool spread
// across both clusters runs every graph node at the LITTLE cores' pace. Measured on the Boox
// Tab Mini C (4x A73 + 4x A53): the llama.cpp predecessor was bimodal at 0.63 vs 6.1 tok/s on
// exactly this, and the LiteRT ASR engines — which never pinned — were measured at 2.7-2.95x
// realtime when the scheduler happened to place the pool well and 0.84-1.38x when it did not,
// on byte-identical input. Same binary, same clip, ~2-3x apart, with no thermal component
// (43 C, all big cores at max frequency).
//
// WHAT WAS WRONG WITH THE PREVIOUS POLICY
// ---------------------------------------
// Both engines used to select cores whose max frequency EXACTLY equalled the maximum:
//
//     big = count(freqs == topFreq)
//
// That is right on a symmetric 4+4 part (the Boox: 4 cores at 2016 MHz). It is wrong on a
// TRI-CLUSTER part, which is most of the modern Android market — Snapdragon 888 is
// 4x A55 @1.80 + 3x A78 @2.42 + 1x X1 @2.84, so `topFreq` matches exactly ONE core. The pool
// was then pinned to the single prime core and the thread count clamped to 1, leaving three
// A78s idle. Same shape on 8 Gen 1-3, Dimensity 9000+, Exynos 2100+.
//
// THE POLICY HERE
// ---------------
// Group cores into clusters by max frequency, take the fastest cluster, then keep merging the
// next cluster down while the selection is smaller than kMinCores AND that cluster is within
// kMergeRatio of the top frequency. So:
//   4+4 symmetric (Boox)      -> the 4 big cores, as before.
//   1+3+4 tri-cluster         -> prime + big = 4 cores, instead of 1.
//   2+6 / 6+2                 -> the fast cluster, merged only if it is tiny.
//   no cpufreq (emulator)     -> every core; never pin to nothing.
//
// LIMITS — this is a heuristic, not a guarantee:
//   * A backgrounded app is confined to the little cluster by cgroup cpuset, which overrides
//     affinity. Transcription runs in a foreground service, which mitigates but does not
//     guarantee this.
//   * Frequency is a proxy for capacity. It is a good one within a SoC, but two clusters at the
//     same clock can still differ in IPC (A78 vs A55 never collide this way in practice).
//   * Big-core-only placement raises power and heat. On a passively cooled e-ink device that is
//     free; on a phone under sustained load it can provoke throttling. We accept that: the work
//     is user-visible and foreground, and the alternative is a 2-3x random slowdown.
//   * Verified on the Boox (4+4) and the Pi 4 (uniform). The tri-cluster path is reasoned from
//     published topologies, NOT measured — no such device was available.
#pragma once

#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <vector>

namespace voxsum {

/** Cores below this count in the top cluster trigger a merge with the next one down. Four keeps
 *  a tri-cluster part at prime+big rather than collapsing onto the single prime core. */
inline constexpr int kMinCores = 3;
/** Only merge a slower cluster if it is within this fraction of the top clock — a 1.80 GHz A55
 *  cluster must never be merged into a 2.84 GHz selection just to reach kMinCores. */
inline constexpr double kMergeRatio = 0.75;

inline unsigned long long cpu_max_freq(int c) {
    char p[128];
    snprintf(p, sizeof(p), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
    unsigned long long f = 0;
    if (FILE* fp = fopen(p, "r")) {
        if (fscanf(fp, "%llu", &f) != 1) f = 0;
        fclose(fp);
    }
    return f;
}

struct CpuTopology {
    int online = 1;
    /** Number of cores in [mask]; the sane upper bound for a worker pool. */
    int fast = 1;
    unsigned long long topFreq = 0;
    /** Lowest max-frequency accepted into the selection (0 = accept everything). */
    unsigned long long cutoffFreq = 0;
};

inline const CpuTopology& cpu_topology() {
    static const CpuTopology t = [] {
        CpuTopology r;
        r.online = std::max(1, (int) sysconf(_SC_NPROCESSORS_ONLN));
        std::vector<unsigned long long> freqs;
        freqs.reserve(r.online);
        for (int c = 0; c < r.online; ++c) freqs.push_back(cpu_max_freq(c));
        r.topFreq = *std::max_element(freqs.begin(), freqs.end());
        if (r.topFreq == 0) {          // no cpufreq exposed — treat every core as fast
            r.fast = r.online;
            r.cutoffFreq = 0;
            return r;
        }
        // Distinct cluster frequencies, fastest first.
        std::vector<unsigned long long> tiers(freqs);
        std::sort(tiers.begin(), tiers.end(), std::greater<unsigned long long>());
        tiers.erase(std::unique(tiers.begin(), tiers.end()), tiers.end());

        unsigned long long cutoff = tiers.front();
        int selected = (int) std::count(freqs.begin(), freqs.end(), cutoff);
        for (size_t i = 1; i < tiers.size() && selected < kMinCores; ++i) {
            if ((double) tiers[i] < kMergeRatio * (double) r.topFreq) break;
            cutoff = tiers[i];
            selected += (int) std::count(freqs.begin(), freqs.end(), tiers[i]);
        }
        r.cutoffFreq = cutoff;
        r.fast = std::max(1, selected);
        return r;
    }();
    return t;
}

/** Cores in the fast selection — the ceiling for a worker pool. Never 0. */
inline int fast_core_count() { return cpu_topology().fast; }

/** Restrict the CALLING thread to the fast selection. Worker pools (ggml, XNNPACK/LiteRT)
 *  INHERIT the creating thread's mask, so call this BEFORE the pool is spawned.
 *  Best-effort: a failure (typically a cgroup cpuset in the background) is not fatal. */
inline bool pin_to_fast_cores() {
    const CpuTopology& t = cpu_topology();
    if (t.topFreq == 0) return false;      // nothing to distinguish — leave the mask alone
    cpu_set_t set;
    CPU_ZERO(&set);
    int n = 0;
    for (int c = 0; c < t.online && c < CPU_SETSIZE; ++c) {
        if (cpu_max_freq(c) >= t.cutoffFreq) { CPU_SET(c, &set); ++n; }
    }
    if (n == 0) return false;
    return sched_setaffinity(0, sizeof(set), &set) == 0;
}

/** Widen the CALLING thread back to every online core — for memory-bound or IO-bound phases
 *  where the little cluster contributes instead of gating. */
inline bool unpin_to_all_cores() {
    const CpuTopology& t = cpu_topology();
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c = 0; c < t.online && c < CPU_SETSIZE; ++c) CPU_SET(c, &set);
    return sched_setaffinity(0, sizeof(set), &set) == 0;
}

}  // namespace voxsum
