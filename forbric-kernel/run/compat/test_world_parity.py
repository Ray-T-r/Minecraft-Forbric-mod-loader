"""world-parity.py: which spawner differences are asserted and which are evidence."""
import importlib.util
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location("world_parity", Path(__file__).with_name("world-parity.py"))
wp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(wp)


def chunk(**spawners):
    return {"_spawners": {tuple(int(v) for v in pos.split("_")[1:]): mob for pos, mob in spawners.items()}}


class SpawnerFacets(unittest.TestCase):
    def test_a_different_mob_at_the_same_spot_is_a_difference(self):
        a, b = chunk(p_1_2_3="minecraft:zombie"), chunk(p_1_2_3="minecraft:skeleton")
        self.assertTrue(wp.differs("spawner_mobs", a, b))
        self.assertFalse(wp.differs("spawner_positions", a, b))

    def test_a_spawner_placed_in_the_neighbouring_chunk_is_only_a_position_difference(self):
        a, b = chunk(p_1_2_3="minecraft:cave_spider"), chunk()
        self.assertFalse(wp.differs("spawner_mobs", a, b))
        self.assertTrue(wp.differs("spawner_positions", a, b))

    def test_the_same_spawners_do_not_differ(self):
        a = chunk(p_1_2_3="minecraft:zombie", p_4_5_6="minecraft:spider")
        b = chunk(p_4_5_6="minecraft:spider", p_1_2_3="minecraft:zombie")
        self.assertFalse(wp.differs("spawner_mobs", a, b))
        self.assertFalse(wp.differs("spawner_positions", a, b))

    def test_other_facets_compare_their_digests(self):
        self.assertTrue(wp.differs("biomes", {"biomes": "x"}, {"biomes": "y"}))
        self.assertFalse(wp.differs("biomes", {"biomes": "x"}, {"biomes": "x"}))


if __name__ == "__main__":
    unittest.main()
