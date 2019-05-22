import unittest

from sketch.frequent import SpaceSavingSketch, CountMinSketch


class SketchTest(unittest.TestCase):
    def test_spacesaving(self):
        xs = list(range(100)) + [1] * 50
        ss = SpaceSavingSketch(size=10)
        ss.add(xs)
        ss_counts = ss.get_dict()
        self.assertLess(ss_counts[1], 70)
        self.assertGreater(ss_counts[1], 50)

        ss.add(xs)
        ss_counts = ss.get_dict()
        self.assertLess(ss_counts[1], 120)
        self.assertGreater(ss_counts[1], 100)

    def test_countmin(self):
        xs = list(range(100)) + [1] * 50
        cms = CountMinSketch(size=10, unbiased=True)
        cms.add(xs)
        cms_counts = cms.get_dict()
        self.assertLessEqual(cms_counts[1], 55)