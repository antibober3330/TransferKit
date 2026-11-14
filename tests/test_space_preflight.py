import unittest
from itertools import product

from transferkit.space_preflight import (
    MIB,
    SpaceItemEstimate,
    SpaceStrategy,
    calculate_space_preflight,
    default_safety_margin,
    select_drop_largest_subset,
)


def estimates(*sizes: int) -> tuple[SpaceItemEstimate, ...]:
    return tuple(SpaceItemEstimate(str(index), size, size) for index, size in enumerate(sizes))


class SpacePreflightTests(unittest.TestCase):
    def test_batch_is_default_when_all_items_fit_with_margin(self):
        result = calculate_space_preflight(
            estimates(10, 20), allocatable_bytes=100, safety_margin_bytes=0
        )

        self.assertEqual(result.strategy, SpaceStrategy.BATCH)
        self.assertEqual(result.batch_peak_bytes, 60)
        self.assertEqual(result.streaming_peak_bytes, 50)
        self.assertFalse(result.requires_user_decision)
        self.assertEqual(result.kept_item_ids, ("0", "1"))

    def test_streaming_is_selected_if_batch_does_not_fit(self):
        result = calculate_space_preflight(
            estimates(30, 30), allocatable_bytes=100, safety_margin_bytes=0
        )

        self.assertEqual(result.strategy, SpaceStrategy.STREAMING)
        self.assertEqual(result.batch_peak_bytes, 120)
        self.assertEqual(result.streaming_peak_bytes, 90)
        self.assertTrue(result.requires_user_decision)

    def test_drop_largest_keeps_maximum_feasible_count(self):
        result = calculate_space_preflight(
            estimates(10, 30, 40), allocatable_bytes=100, safety_margin_bytes=0
        )

        self.assertEqual(result.strategy, SpaceStrategy.DROP_LARGEST)
        self.assertEqual(result.kept_item_ids, ("0", "1"))
        self.assertEqual(result.skipped_item_ids, ("2",))
        self.assertTrue(result.requires_user_decision)

    def test_drop_largest_regression_keeps_all_10_20_30_items_at_budget_100(self):
        chosen = select_drop_largest_subset(estimates(10, 20, 30), safe_available_bytes=100)

        self.assertEqual(tuple(item.item_id for item in chosen), ("0", "1", "2"))

    def test_drop_largest_maximizes_count_for_asymmetric_provider_estimates(self):
        cost_pairs = ((0, 0), (1, 3), (3, 1))
        for pairs in product(cost_pairs, repeat=4):
            items = tuple(
                SpaceItemEstimate(str(index), staging, restore)
                for index, (staging, restore) in enumerate(pairs)
            )
            for budget in range(11):
                with self.subTest(estimates=pairs, budget=budget):
                    chosen = select_drop_largest_subset(items, safe_available_bytes=budget)
                    feasible_counts = [
                        len(subset)
                        for mask in range(1 << len(items))
                        if (
                            subset := tuple(
                                item for index, item in enumerate(items) if mask & (1 << index)
                            )
                        )
                        and sum(item.restore_bytes or 0 for item in subset)
                        + max(item.staging_bytes or 0 for item in subset)
                        <= budget
                    ]
                    expected_count = max(feasible_counts, default=0)
                    self.assertEqual(len(chosen), expected_count)

    def test_insufficient_when_even_smallest_item_does_not_fit(self):
        result = calculate_space_preflight(
            estimates(60, 70), allocatable_bytes=100, safety_margin_bytes=0
        )

        self.assertEqual(result.strategy, SpaceStrategy.INSUFFICIENT)
        self.assertEqual(result.kept_item_ids, ())
        self.assertEqual(result.skipped_item_ids, ())
        self.assertTrue(result.requires_user_decision)

    def test_provider_unknown_estimate_never_means_zero_bytes(self):
        result = calculate_space_preflight(
            (SpaceItemEstimate("known", 10, 10), SpaceItemEstimate("unknown", None, 5)),
            allocatable_bytes=1_000,
            safety_margin_bytes=0,
        )

        self.assertIsNone(result.strategy)
        self.assertEqual(result.unknown_item_ids, ("unknown",))
        self.assertEqual(result.kept_item_ids, ())
        self.assertTrue(result.requires_user_decision)
        self.assertIsNone(result.batch_peak_bytes)

    def test_provider_estimates_can_differ_from_source_size(self):
        result = calculate_space_preflight(
            (SpaceItemEstimate("provider-row", 8, 32),),
            allocatable_bytes=50,
            safety_margin_bytes=0,
        )

        self.assertEqual(result.total_staging_bytes, 8)
        self.assertEqual(result.total_restore_bytes, 32)
        self.assertEqual(result.batch_peak_bytes, 40)
        self.assertEqual(result.streaming_peak_bytes, 40)
        self.assertEqual(result.strategy, SpaceStrategy.BATCH)

    def test_default_margin_is_larger_of_minimum_and_five_percent(self):
        self.assertEqual(default_safety_margin(100), 100 * MIB)
        self.assertEqual(default_safety_margin(10_000 * MIB), 500 * MIB)

    def test_empty_selection_is_a_zero_cost_batch(self):
        result = calculate_space_preflight((), allocatable_bytes=0, safety_margin_bytes=0)

        self.assertEqual(result.strategy, SpaceStrategy.BATCH)
        self.assertEqual(result.batch_peak_bytes, 0)
        self.assertEqual(result.streaming_peak_bytes, 0)

    def test_duplicate_item_ids_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "unique"):
            calculate_space_preflight(
                (SpaceItemEstimate("same", 1, 1), SpaceItemEstimate("same", 2, 2)),
                allocatable_bytes=10,
                safety_margin_bytes=0,
            )

    def test_invalid_estimates_and_budgets_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "staging_bytes"):
            SpaceItemEstimate("bad", True, 1)
        with self.assertRaisesRegex(ValueError, "allocatable_bytes"):
            calculate_space_preflight((), allocatable_bytes=-1)
        with self.assertRaisesRegex(ValueError, "safety_margin_bytes"):
            calculate_space_preflight((), allocatable_bytes=1, safety_margin_bytes=-1)

    def test_drop_largest_rejects_unknown_estimates(self):
        with self.assertRaisesRegex(ValueError, "known"):
            select_drop_largest_subset(
                (SpaceItemEstimate("unknown", None, 1),), safe_available_bytes=10
            )


if __name__ == "__main__":
    unittest.main()
