import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import cleanup as c

REPO = "243919538384.dkr.ecr.ap-northeast-2.amazonaws.com/vium-be"


def image(n):
    return "sha256:" + f"{n:064x}"


def release(n, success=True, ref=None, kind="sha"):
    name = f"{n:040x}.ABC123" if kind == "sha" else f"{kind}.ABC123"
    return c.Release(name, Path("/releases") / name, kind, n if success else None,
                     ref or REPO + "@" + image(n))


class SelectionTests(unittest.TestCase):
    def test_keeps_three_distinct_images_and_bootstrap(self):
        items = [release(n) for n in range(1, 7)] + [release(7, False), release(8, False), release(0, kind="bootstrap")]
        plan = c.select_releases(items, items[5].path, items[4].path)
        self.assertEqual({r.path for r in plan.keep}, {r.path for r in items[3:6]} | {items[-1].path})
        self.assertEqual(len(plan.delete), 5)

    def test_duplicate_images_extend_success_window(self):
        items = [release(1), release(2), release(3), release(4, ref=release(3).image)]
        self.assertEqual(len(c.select_releases(items, items[-1].path, items[-2].path).keep), 4)

    def test_old_previous_is_always_kept(self):
        items = [release(n) for n in range(6)]
        plan = c.select_releases(items, items[-1].path, items[0].path)
        self.assertIn(items[0], plan.keep)
        self.assertEqual(len(plan.keep), 4)

    def test_few_successes_only_delete_failed(self):
        items = [release(1), release(2), release(3, False)]
        self.assertEqual(c.select_releases(items, items[0].path, items[1].path).delete, [items[2]])

    def test_unknown_is_neither_kept_nor_deleted(self):
        items = [release(1), release(2, kind="unknown")]
        plan = c.select_releases(items, items[0].path, items[0].path)
        self.assertEqual(plan.unknown, [items[1]])
        self.assertNotIn(items[1], plan.keep + plan.delete)

    def test_missing_current_or_previous_aborts(self):
        item = release(1)
        for current, previous in ((Path('/missing'), item.path), (item.path, Path('/missing'))):
            with self.assertRaises(ValueError):
                c.select_releases([item], current, previous)

    def test_unknown_recent_image_aborts_selection(self):
        item = release(1)
        bad = c.Release(item.name, item.path, 'sha', 1, None)
        with self.assertRaises(ValueError):
            c.select_releases([bad], bad.path, bad.path)

    def test_image_selection_respects_all_references_and_protection(self):
        images = [c.LocalImage(image(n), (REPO + '@' + image(n),), ()) for n in range(1, 5)]
        images += [c.LocalImage(image(5), (REPO + '@' + image(5),), ('other:latest',)),
                   c.LocalImage(image(6), (), ('other:latest',)),
                   c.LocalImage(image(7), (), (REPO + '2:tag',)),
                   c.LocalImage(image(8), (), (REPO + ':tag',))]
        delete, skipped = c.select_images(images, REPO, {image(1), image(2)}, {image(3)})
        self.assertEqual(delete, [image(4), image(8)])
        self.assertEqual(skipped, [image(5)])

    def test_parse_image_requires_exactly_one_quoted_line(self):
        valid = 'services:\n  app:\n    image: "ref"\n'
        self.assertEqual(c.parse_image_reference(valid), 'ref')
        for text in ('services: {}', valid + valid, '    image: ref\n'):
            with self.assertRaises(ValueError):
                c.parse_image_reference(text)

    def test_summary_rejects_paths_and_invalid_keys(self):
        self.assertEqual(c.summary_line(dict(status='ok', images_removed=2)), 'CLEANUP status=ok images_removed=2')
        for fields in ({'path': '/opt'}, {'bad/key': 2}, {'count': -1}, {'count': True}):
            with self.assertRaises(ValueError):
                c.summary_line(fields)

    def test_lifecycle_policy(self):
        rules = json.loads(Path(__file__).with_name('ecr-lifecycle-policy.json').read_text())['rules']
        priorities = [r['rulePriority'] for r in rules]
        self.assertEqual(priorities, sorted(set(priorities)))
        self.assertEqual(rules[-1]['selection']['tagStatus'], 'any')
        count = rules[-1]['selection']['countNumber']
        self.assertGreater(count, c.KEEP_DISTINCT_IMAGES)
        self.assertGreaterEqual(count, 10)
        self.assertEqual(rules[-1]['selection']['countType'], 'imageCountMoreThan')
        self.assertEqual(rules[0]['selection'], dict(tagStatus='untagged', countType='sinceImagePushed', countUnit='days', countNumber=7))


class FilesystemTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.releases = self.root / 'releases'
        self.releases.mkdir()
        self.paths = []
        for n in range(1, 6):
            path = self.releases / release(n).name
            path.mkdir()
            (path / 'compose.image.yml').write_text(f'    image: "{REPO}@{image(n)}"\n')
            (path / 'succeeded').touch()
            os.utime(path / 'succeeded', (n, n))
            self.paths.append(path)
        (self.root / 'current').symlink_to(self.paths[-1])

    def run_cleanup(self, *extra):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
            result = c.main(['--root', str(self.root), '--previous', str(self.paths[-2]),
                             '--repository', REPO, '--docker-root', str(self.root), *extra])
        self.assertEqual(result, 0)
        return output.getvalue()

    def test_internal_symlinks_do_not_delete_shared_files(self):
        shared = self.root / 'shared'
        (shared / 'certs').mkdir(parents=True)
        (shared / 'certs/cert').write_text('certificate')
        (shared / '.env.prod').write_text('secret')
        target = self.paths[0]
        (target / 'deploy').mkdir()
        (target / 'deploy/certs').symlink_to(shared / 'certs')
        (target / '.env.prod').symlink_to(shared / '.env.prod')
        c.remove_release(target, self.releases, {self.paths[-1]})
        self.assertEqual((shared / '.env.prod').read_text(), 'secret')
        self.assertEqual((shared / 'certs/cert').read_text(), 'certificate')

    def test_deletion_rejects_outside_symlink_and_protected_path(self):
        outside = self.root / release(10).name
        outside.mkdir()
        link = self.releases / release(11).name
        link.symlink_to(outside)
        for target in (outside, link, self.paths[-1]):
            with self.assertRaises(ValueError):
                c.remove_release(target, self.releases, {self.paths[-1]})

    def test_scan_marks_symlinks_and_files_unknown(self):
        (self.releases / release(10).name).symlink_to(self.paths[0])
        (self.releases / 'random').write_text('file')
        self.assertEqual(sum(r.kind == 'unknown' for r in c.scan_releases(self.releases)), 2)

    def test_image_lookup_failure_still_removes_old_directories(self):
        with patch.object(c, 'resolve_image_id', side_effect=ValueError), patch.object(c, 'remove_image') as remove:
            output = self.run_cleanup()
        self.assertIn('status=partial', output)
        self.assertIn('images_skipped=all', output)
        self.assertFalse(self.paths[0].exists())
        remove.assert_not_called()

    def test_dry_run_does_not_delete_anything(self):
        with patch.object(c, 'resolve_image_id', side_effect=lambda ref: ref.split('@')[1]), \
             patch.object(c, 'container_image_ids', return_value=set()), \
             patch.object(c, 'list_local_images', return_value=[c.LocalImage(image(1), (REPO+'@'+image(1),), ())]), \
             patch.object(c, 'remove_image') as remove:
            output = self.run_cleanup('--dry-run')
        self.assertIn('dry_run=1', output)
        self.assertIn('images_removed=1', output)
        self.assertTrue(all(p.exists() for p in self.paths))
        remove.assert_not_called()

    def test_failed_directory_deletion_preserves_its_image(self):
        with patch.object(c, 'remove_release', side_effect=OSError), \
             patch.object(c, 'resolve_image_id', side_effect=lambda ref: ref.split('@')[1]), \
             patch.object(c, 'container_image_ids', return_value=set()), \
             patch.object(c, 'list_local_images', return_value=[c.LocalImage(image(1), (REPO+'@'+image(1),), ())]), \
             patch.object(c, 'remove_image') as remove:
            output = self.run_cleanup()
        self.assertIn('status=partial', output)
        remove.assert_not_called()

    def test_invalid_current_deletes_nothing(self):
        (self.root / 'current').unlink()
        (self.root / 'current').symlink_to(self.root)
        with patch.object(c, 'remove_release') as remove, patch.object(c, 'docker') as docker:
            output = self.run_cleanup()
        self.assertIn('status=error', output)
        remove.assert_not_called()
        docker.assert_not_called()

    def test_docker_inventory_failure_prevents_all_image_deletions(self):
        with patch.object(c, 'resolve_image_id', return_value=image(5)), \
             patch.object(c, 'container_image_ids', return_value=set()), \
             patch.object(c, 'list_local_images', side_effect=subprocess.TimeoutExpired('docker', 60)), \
             patch.object(c, 'remove_image') as remove:
            self.assertIn('images_skipped=all', self.run_cleanup())
        remove.assert_not_called()

    def test_image_removal_failure_is_partial_and_continues(self):
        images = [c.LocalImage(image(n), (REPO+'@'+image(n),), ()) for n in (1, 2)]
        with patch.object(c, 'resolve_image_id', side_effect=lambda ref: ref.split('@')[1]), \
             patch.object(c, 'container_image_ids', return_value=set()), \
             patch.object(c, 'list_local_images', return_value=images), \
             patch.object(c, 'remove_image', side_effect=[OSError, None]) as remove:
            output = self.run_cleanup()
        self.assertIn('images_failed=1', output)
        self.assertIn('images_removed=1', output)
        self.assertEqual(remove.call_count, 2)


if __name__ == '__main__':
    unittest.main()
