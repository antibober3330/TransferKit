import unittest

from transferkit.contracts import CategoryId, DeviceInfo, ManifestCategory, ManifestItem, TransferManifest
from transferkit.dedup import (
    TargetInventory,
    TargetInventoryItem,
    build_exact_dedup_plan,
)


def make_manifest(items_by_category):
    return TransferManifest(
        version="1.0",
        transfer_id="00000000-0000-4000-8000-000000000001",
        created_at="2026-11-12T12:00:00Z",
        source_device=DeviceInfo(
            manufacturer="Example",
            model="Source",
            sdk=34,
            fingerprint="example/source",
        ),
        categories={
            category: ManifestCategory(provider="test", method="fixture", items=items)
            for category, items in items_by_category.items()
        },
    )


def item(item_id, size, digest):
    return ManifestItem(id=item_id, size=size, sha256=digest, metadata={"path": f"{item_id}.jpg"})


class ExactDedupPlanTests(unittest.TestCase):
    def test_exact_hash_and_size_match_is_skipped_even_when_names_differ(self):
        manifest = make_manifest(
            {CategoryId.PHOTOS: [item("viber_photo", 200, "a" * 64), item("new_photo", 80, "b" * 64)]}
        )
        target = TargetInventory(
            items=(TargetInventoryItem(CategoryId.PHOTOS, "row-17", 200, "a" * 64),),
            complete_categories=frozenset({CategoryId.PHOTOS}),
        )

        plan = build_exact_dedup_plan(manifest, target)

        self.assertEqual(plan.item_count_skipped, 1)
        self.assertEqual(plan.skipped_duplicates[0].source_item_id, "viber_photo")
        self.assertEqual(plan.skipped_duplicates[0].target_item_id, "row-17")
        self.assertEqual(plan.bytes_skipped, 200)
        self.assertEqual(plan.bytes_to_transfer, 80)
        self.assertEqual([entry[1].id for entry in plan.transfer_items], ["new_photo"])

    def test_incomplete_or_unavailable_category_never_causes_a_skip(self):
        manifest = make_manifest({CategoryId.PHOTOS: [item("photo", 25, "a" * 64)]})
        target = TargetInventory(
            items=(TargetInventoryItem(CategoryId.PHOTOS, "row-1", 25, "a" * 64),),
            complete_categories=frozenset(),
        )

        plan = build_exact_dedup_plan(manifest, target)

        self.assertEqual(plan.item_count_skipped, 0)
        self.assertEqual(plan.bytes_to_transfer, 25)

    def test_size_and_category_are_part_of_the_exact_match_key(self):
        manifest = make_manifest(
            {
                CategoryId.PHOTOS: [item("photo", 25, "a" * 64)],
                CategoryId.DOWNLOADS: [item("download", 25, "a" * 64)],
            }
        )
        target = TargetInventory(
            items=(
                TargetInventoryItem(CategoryId.PHOTOS, "wrong-size", 26, "a" * 64),
                TargetInventoryItem(CategoryId.DOWNLOADS, "photo-copy", 25, "a" * 64),
            ),
            complete_categories=frozenset({CategoryId.PHOTOS, CategoryId.DOWNLOADS}),
        )

        plan = build_exact_dedup_plan(manifest, target)

        self.assertEqual(plan.item_count_skipped, 1)
        self.assertEqual(plan.skipped_duplicates[0].category_id, CategoryId.DOWNLOADS)
        self.assertEqual(plan.transfer_items[0][1].id, "photo")

    def test_ambiguous_target_matches_choose_stable_reference_without_deleting(self):
        manifest = make_manifest({CategoryId.PHOTOS: [item("photo", 25, "a" * 64)]})
        target = TargetInventory(
            items=(
                TargetInventoryItem(CategoryId.PHOTOS, "row-z", 25, "a" * 64),
                TargetInventoryItem(CategoryId.PHOTOS, "row-a", 25, "a" * 64),
            ),
            complete_categories=frozenset({CategoryId.PHOTOS}),
        )

        plan = build_exact_dedup_plan(manifest, target)

        self.assertEqual(plan.skipped_duplicates[0].target_item_id, "row-a")
        self.assertEqual(len(target.items), 2)  # planning has no destructive side effects

    def test_bad_inventory_digest_or_duplicate_reference_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "lowercase SHA-256"):
            TargetInventoryItem(CategoryId.PHOTOS, "row-1", 10, "A" * 64)
        item_ref = TargetInventoryItem(CategoryId.PHOTOS, "row-1", 10, "a" * 64)
        with self.assertRaisesRegex(ValueError, "must be unique"):
            TargetInventory((item_ref, item_ref), frozenset({CategoryId.PHOTOS}))


if __name__ == "__main__":
    unittest.main()
