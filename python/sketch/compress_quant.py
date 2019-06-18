import math
from typing import Dict, Any, List
import numpy as np
import random
import sketch.quantile_cy
import tdigest.tdigest
import sortednp as snp


class RankTracker:
    def __init__(self, x_tracked: List):
        self.x_tracked = np.array(x_tracked)

    def compress(
            self,
            xs,
    ) -> Dict[Any, float]:
        # negate to make ranges (l,r]
        bin_edges = np.concatenate([[-np.inf], self.x_tracked])
        bin_weights, _ = np.histogram(-xs, -bin_edges[::-1])
        x_counts = {self.x_tracked[i]: bin_weights[::-1][i] for i in range(len(self.x_tracked))}
        return x_counts

class SkipCompressor:
    def __init__(self, size, seed=0, biased=False):
        self.size = size
        self.random = random.Random()
        self.random.seed(seed)
        self.biased = biased

    def compress(
            self,
            x_sorted
    ) -> Dict[Any, float]:
        n = len(x_sorted)
        skip = int(math.ceil(n/self.size))
        saved = dict()

        start_idx = 0
        end_idx = skip
        while end_idx <= n:
            if self.biased:
                seg_offset = skip // 2
            else:
                seg_offset = self.random.randrange(0, skip)
            to_save = x_sorted[start_idx + seg_offset]
            saved[to_save] = skip
            start_idx = end_idx
            end_idx += skip

        if end_idx > n and start_idx < n:
            seg_size = n - start_idx
            if self.biased:
                seg_offset = seg_size // 2
            else:
                seg_offset = self.random.randrange(0, seg_size)
            to_save = x_sorted[start_idx + seg_offset]
            saved[to_save] = seg_size

        return saved


class QRandomSampleCompressor:
    def __init__(self, size, seed=0, unbiased=True):
        self.size = size
        self.random = np.random.RandomState(seed=seed)

    def compress(
            self,
            xs
    ) -> Dict[Any, float]:
        new_size = self.size
        sampled = self.random.choice(xs, size=new_size, replace=False)

        compressed_items = dict()
        inc_amt = len(xs) / new_size
        for x in sampled:
            compressed_items[x] = compressed_items.get(x, 0.0) + inc_amt

        return compressed_items


def loss(f):
    return f**2
# def loss(f):
#     a=1
#     return np.cosh(a*f)

def find_next_c(xvals, saved, saved_weight, new_weight):
    # print("finding")
    # print("range:{}-{}".format(xvals[0],xvals[-1]))
    # print("saved:{}".format(saved))
    d = np.asarray(sketch.quantile_cy.fast_delta(xvals, saved, saved_weight))
    # l_diff = loss(d-new_weight) - loss(d)
    scale_f = new_weight
    l_diff = loss((d-new_weight)/scale_f) - loss(d/scale_f)
    l_diff_suff = np.cumsum(l_diff[::-1])[::-1]
    l_diff_best = np.argmax(-l_diff_suff)
    # print("best:{}".format(xvals[l_diff_best]))
    return xvals[l_diff_best], np.sum(loss(d/scale_f))


class CoopCompressor:
    def __init__(self, size):
        self.size = size
        self.running_stored = np.array([], dtype=float)
        self.stored_weights = np.array([], dtype=float)
        self.running_actual = np.array([], dtype=float)

    def compress(
            self,
            xs
    ) -> Dict[Any, float]:
        x_segs = np.array_split(xs, self.size)
        self.running_actual = snp.merge(self.running_actual, xs)
        # self.running_actual = np.append(
        #     self.running_actual,
        #     xs
        # )
        # self.running_actual.sort()
        to_save = dict()
        for cur_seg in x_segs:
            seg_start, seg_end = cur_seg[0], cur_seg[-1]

            cur_actual = self.running_actual[
                (self.running_actual >= seg_start)
                & (self.running_actual <= seg_end)
                ]
            stored_mask = (
                (self.running_stored >= seg_start)
                & (self.running_stored <= seg_end)
            )
            cur_stored = self.running_stored[stored_mask]
            cur_stored_weights = self.stored_weights[stored_mask]

            cur_seg_weight = len(cur_seg)
            cur_to_save,_ = find_next_c(cur_actual, cur_stored, cur_stored_weights, cur_seg_weight)
            to_save[cur_to_save] = cur_seg_weight

        new_saved = []
        new_weights = []
        for cur_save, cur_weight in to_save.items():
            new_saved.append(cur_save)
            new_weights.append(cur_weight)

        self.running_stored = np.append(
            self.running_stored,
            new_saved
        )
        self.stored_weights = np.append(
            self.stored_weights,
            new_weights
        )
        arg_idx = np.argsort(self.running_stored)
        self.running_stored = self.running_stored[arg_idx]
        self.stored_weights = self.stored_weights[arg_idx]

        return to_save
