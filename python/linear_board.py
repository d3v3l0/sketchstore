import math
import os
from typing import Sequence

import numpy as np
import pandas as pd
from tqdm import tqdm

import sketch.compress_freq as cf
import sketch.compress_quant as cq
import sketch.compress_dyadic as cd
import sketch.sketch_frequent as f
import sketch.sketch_gen as board_sketch
import storyboard.board_gen as board_gen


def get_tracked(data_name) -> np.ndarray:
    x_to_track = []
    if data_name == "caida_1M":
        x_df = pd.read_csv("notebooks/caida1M-xtrack.csv")
        x_to_track = x_df["x_track"].values
    elif data_name == "caida_10M":
        x_df = pd.read_csv("/Users/edwardgan/Documents/Projects/datasets/caida-pcap/caida10M-ipdst-xtrack.csv")
        x_to_track = x_df["x_track"].values[:200]
    elif data_name == "uniform_1M":
        x_to_track = np.linspace(0, 1, 101)
    elif data_name == "power_2M":
        x_df = pd.read_csv("/Users/edwardgan/Documents/Projects/datasets/household/power_tracked.csv")
        x_to_track = x_df["x_track"].values
    elif data_name == "zipf1p1_10M":
        x_df = pd.read_csv("notebooks/zipf10M-xtrack.csv")
        x_to_track = x_df["x_track"].values[:200]
    else:
        raise Exception("Invalid Dataset: {}".format(data_name))
    return np.sort(x_to_track)


def get_dataset(data_name) -> np.ndarray:
    x_stream = None
    x_to_track = None
    if data_name == "caida_1M":
        df_in = pd.read_csv("notebooks/caida1M-dest-stream.csv")
        x_stream = df_in["Destination"].values
    elif data_name == "caida_10M":
        df_in = pd.read_csv("/Users/edwardgan/Documents/Projects/datasets/caida-pcap/caida10M-ipdst.csv")
        x_stream = df_in["ip.dst"].values
    elif data_name == "zipf1p1_10M":
        df_in = pd.read_csv("notebooks/zipf10M.csv", nrows=10_000_000)
        x_stream = df_in["x"].values
    elif data_name == "uniform_1M":
        r = np.random.RandomState(0)
        x_stream = r.uniform(0, 1, size=1_000_000)
    elif data_name == "power_2M":
        df_in = pd.read_csv("/Users/edwardgan/Documents/Projects/datasets/household/power.csv")
        x_stream = df_in["Global_active_power"].values
    else:
        raise Exception("Invalid Dataset: {}".format(data_name))
    return x_stream


def get_dyadic_base(sketch_name: str) -> int:
    return int(sketch_name[sketch_name.rfind("_b") + 2:])


def get_dyadic_adjusted_size(size: int, base: int, max_segments: int):
    dyadic_height = int(math.log(max_segments, base))
    return dyadic_height, size / (dyadic_height + 1)


def get_sketch_gen(sketch_name: str, x_to_track: Sequence = None) -> board_sketch.SketchGen:
    sketch_gen = None
    if sketch_name == "top_values":
        sketch_gen = board_sketch.ItemDictCompressorGen(
            name=sketch_name,
            compressor=cf.TopValueCompressor(x_to_track=x_to_track)
        )
    elif sketch_name == "cooperative":
        sketch_gen = board_sketch.ItemDictCompressorGen(
            name=sketch_name,
            compressor=cf.IncrementalRangeCompressor()
        )
    elif sketch_name == "random_sample":
        sketch_gen = board_sketch.ItemDictCompressorGen(
            name=sketch_name,
            compressor=cf.UniformSamplingCompressor()
        )
    elif sketch_name == "cms_min":
        sketch_gen = board_sketch.CMSGen()
    elif sketch_name == "truncation":
        sketch_gen = board_sketch.ItemDictCompressorGen(
            name=sketch_name,
            compressor=cf.TruncationCompressor()
        )
    elif sketch_name == "pps":
        sketch_gen = board_sketch.ItemDictCompressorGen(
            name=sketch_name,
            compressor=cf.PPSCompressor()
        )
    elif sketch_name.startswith("dyadic"):
        base = get_dyadic_base(sketch_name)
        sketch_gen = board_sketch.DyadicItemDictGen(
            h_compressor=cd.DyadicFrequencyCompressor(max_height=20, base=base)
        )
    ## Quantile Sketches
    elif sketch_name == "q_top_values":
        sketch_gen = board_sketch.SeqDictCompressorGen(
            name=sketch_name,
            compressor=cq.RankTracker(x_tracked=x_to_track)
        )
    elif sketch_name == "q_cooperative":
        sketch_gen = board_sketch.SeqDictCompressorGen(
            name=sketch_name,
            compressor=cq.CoopCompressorFinite()
        )
    elif sketch_name == "q_random_sample":
        sketch_gen = board_sketch.SeqDictCompressorGen(
            name=sketch_name,
            compressor=cq.QRandomSampleCompressor()
        )
    elif sketch_name == "kll":
        sketch_gen = board_sketch.KLLGen()
    elif sketch_name == "q_truncation":
        sketch_gen = board_sketch.SeqDictCompressorGen(
            name=sketch_name,
            compressor=cq.SkipCompressor(biased=True)
        )
    elif sketch_name == "q_pps":
        sketch_gen = board_sketch.SeqDictCompressorGen(
            name=sketch_name,
            compressor=cq.SkipCompressor(biased=False)
        )
    elif sketch_name.startswith("q_dyadic"):
        base = get_dyadic_base(sketch_name)
        sketch_gen = board_sketch.DyadicSeqDictGen(
            h_compressor=cd.DyadicQuantileCompressor(max_height=20, base=base)
        )
    else:
        raise Exception("Invalid Sketch: {}".format(sketch_name))
    return sketch_gen


def get_file_name(data_name: str, granularity: int, sketch_name: str, sketch_size: int):
    dir_name = "output/boards/{data}_{granularity}/".format(
        data=data_name,
        granularity=granularity
    )
    output_file_name = os.path.join(
        dir_name,
        "{}_{}.pkl".format(sketch_name, int(sketch_size))
    )
    return output_file_name


def get_totals_name(data_name: str, granularity: int):
    dir_name = "output/boards/{data}_{granularity}/".format(
        data=data_name,
        granularity=granularity
    )
    output_file_name = os.path.join(
        dir_name,
        "totals.csv"
    )
    return output_file_name


def write_totals(data_name: str, granularity: int, segments: Sequence):
    output_file_name = get_totals_name(data_name, granularity)
    df = pd.DataFrame()
    seg_sizes = [len(segment) for segment in segments]
    df["total"] = seg_sizes
    df["seg_idx"] = list(range(len(segments)))
    df.to_csv(output_file_name, index=False)


def run_test(data_name, cur_granularity, sketch_size, sketch_name):
    x_stream = get_dataset(data_name)
    x_to_track = get_tracked(data_name)
    segments = np.array_split(x_stream, cur_granularity)
    sketch_gen = get_sketch_gen(sketch_name, x_to_track=x_to_track)
    if "dyadic" in sketch_name:
        base = get_dyadic_base(sketch_name)
        dyadic_height, sketch_size = get_dyadic_adjusted_size(
            size=sketch_size, base=base, max_segments=len(segments))
        print("Dyadic Base: {}, Height: {}, Size:{}".format(base, dyadic_height, sketch_size))
    board_constructor = board_gen.BoardGen(sketch_gen)

    segment_times = np.cumsum([len(cur_seg) for cur_seg in segments])
    df = board_constructor.generate(
        segments=segments,
        tags=[{
            "t": t, "size": sketch_size
        } for t in segment_times],
    )
    df["dataset"] = data_name
    output_file_name = get_file_name(
        data_name=data_name,
        granularity=cur_granularity,
        sketch_name=sketch_name,
        sketch_size=sketch_size,
    )
    dir_name = os.path.split(output_file_name)[0]
    print("Output written to: {}".format(output_file_name))
    os.makedirs(dir_name, exist_ok=True)
    write_totals(data_name, granularity=cur_granularity, segments=segments)
    board_constructor.serialize(df, output_file_name)


def main():
    sketch_names = [
        # "q_top_values",
        # "q_random_sample",
        # "q_truncation",
        "q_pps",
        # "q_dyadic_b2",
        # "q_cooperative",
        # "kll"
    ]
    data_name = "power_2M"
    # sketch_names = [
    #     "top_values",
    #     "cooperative",
    #     "random_sample",
    #     "truncation",
    #     "cms_min",
    #     "pps",
    #     "dyadic_b2"
    # ]
    # data_name = "zipf1p1_10M"
    cur_granularity = 2048
    sketch_size = 64
    for sketch_name in sketch_names:
        run_test(
            data_name=data_name,
            cur_granularity=cur_granularity,
            sketch_size=sketch_size,
            sketch_name=sketch_name
        )


if __name__ == "__main__":
    main()