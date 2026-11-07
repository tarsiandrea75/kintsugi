"""Tests of kintsugi_evals.provenance: what a holdout read records, and the refusal without it (ADR-015)."""

import hashlib
import re
import subprocess

import pytest

from kintsugi_evals import provenance
from kintsugi_evals.scoring import read_summary
from test_scoring import TRUTH, write_tsv

PLAN = provenance.REPO / "evals" / "plans" / "2026-11-06-h1-h3-verdicts.md"


def rows_of(tmp_path, split, name="run"):
    run = tmp_path / name
    run.mkdir()
    write_tsv(run / "summary.tsv", [("F1", "COMPLETED", TRUTH, TRUTH, "r1-F1")])
    text = (run / "summary.tsv").read_text().replace("\tdev\t", f"\t{split}\t")
    (run / "summary.tsv").write_text(text)
    return run, read_summary(run, label="A")


def test_no_flags_resolve_to_no_provenance():
    assert provenance.resolve(None, None) is None


@pytest.mark.parametrize("holdout, plan", [("A", None), (None, str(PLAN))])
def test_one_flag_without_the_other_is_refused(holdout, plan):
    with pytest.raises(ValueError, match="--holdout and --plan go together"):
        provenance.resolve(holdout, plan)


def test_a_missing_plan_is_refused(tmp_path):
    with pytest.raises(ValueError, match="does not exist"):
        provenance.resolve("A", str(tmp_path / "nowhere.md"))


def test_a_plan_outside_the_repository_is_refused(tmp_path):
    plan = tmp_path / "plan.md"
    plan.write_text("a plan\n")
    with pytest.raises(ValueError, match="outside the repository"):
        provenance.resolve("A", str(plan))


@pytest.mark.parametrize("name", ["", " ", "A\tB", "A\nB"])
def test_a_blank_or_multiline_holdout_name_is_refused(name):
    with pytest.raises(ValueError, match="--holdout"):
        provenance.resolve(name, str(PLAN))


def test_the_provenance_names_the_plan_from_the_repository_root_with_its_sha256():
    found = provenance.resolve("A", str(PLAN))
    assert found.holdout == "A"
    assert found.plan == "evals/plans/2026-11-06-h1-h3-verdicts.md"
    assert found.plan_sha256 == hashlib.sha256(PLAN.read_bytes()).hexdigest()
    assert re.fullmatch(r"[0-9a-f]{40}|unknown", found.git_commit)


def test_the_commit_is_unknown_when_git_cannot_run(monkeypatch):
    def missing(*args, **kwargs):
        raise OSError("no git")

    monkeypatch.setattr(subprocess, "run", missing)
    assert provenance.git_commit() == "unknown"


def test_the_commit_is_unknown_outside_a_repository(tmp_path):
    assert provenance.git_commit(tmp_path) == "unknown"


@pytest.mark.parametrize("split", ["dev", "stress", "probe", "reference"])
def test_a_split_that_is_no_holdout_needs_no_provenance(tmp_path, split):
    run, rows = rows_of(tmp_path, split)
    provenance.guard([(str(run), rows)], None)


@pytest.mark.parametrize("split", ["holdout", "holdout-2", "hold-out"])
def test_a_holdout_or_unknown_split_needs_the_provenance(tmp_path, split):
    run, rows = rows_of(tmp_path, split)
    with pytest.raises(ValueError, match=f"split {split}.*--holdout NAME and --plan PATH"):
        provenance.guard([(str(run), rows)], None)
    provenance.guard([(str(run), rows)], provenance.resolve("A", str(PLAN)))


def test_the_refusal_names_no_recording(tmp_path):
    run, rows = rows_of(tmp_path, "holdout")
    with pytest.raises(ValueError) as error:
        provenance.guard([(str(run), rows)], None)
    assert "r1-F1" not in str(error.value)


def test_a_committed_input_copy_regenerates_without_the_provenance():
    # A committed result's input copies were read once already; re-scoring them is a regeneration, not a read.
    copy = provenance.RESULTS / "2026-10-30-holdout1-lab" / "inputs" / "A-summary.tsv"
    provenance.guard([(str(copy), read_summary(copy, label="A"))], None)


@pytest.fixture
def results_repo(tmp_path, monkeypatch):
    """A throwaway git repository standing in for the real one, with one committed holdout copy at
    evals/results/r/inputs/A-summary.tsv and the module pointed at it."""
    repo = tmp_path / "repo"
    inputs = repo / "evals" / "results" / "r" / "inputs"
    inputs.mkdir(parents=True)
    source, _ = rows_of(tmp_path, "holdout")
    (inputs / "A-summary.tsv").write_bytes((source / "summary.tsv").read_bytes())
    for args in (["init", "-q"], ["add", "-A"],
                 ["-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "result"]):
        subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True)
    monkeypatch.setattr(provenance, "REPO", repo)
    monkeypatch.setattr(provenance, "RESULTS", repo / "evals" / "results")
    return inputs


def guard_copy(path):
    provenance.guard([(str(path), read_summary(path, label="A"))], None)


def test_a_tracked_committed_copy_is_exempt(results_repo):
    guard_copy(results_repo / "A-summary.tsv")


def test_an_untracked_file_at_a_committed_copy_path_is_refused(results_repo):
    # The exemption is for copies git holds, not for any file put where one would be.
    untracked = results_repo.parent.parent / "s" / "inputs"
    untracked.mkdir(parents=True)
    (untracked / "A-summary.tsv").write_bytes((results_repo / "A-summary.tsv").read_bytes())
    with pytest.raises(ValueError, match="holdout"):
        guard_copy(untracked / "A-summary.tsv")


def test_a_tracked_copy_changed_since_its_commit_is_refused(results_repo):
    copy = results_repo / "A-summary.tsv"
    copy.write_text(copy.read_text() + copy.read_text().splitlines()[1].replace("r1-F1", "r2-F1") + "\n")
    with pytest.raises(ValueError, match="holdout"):
        guard_copy(copy)


def test_the_exemption_fails_closed_without_git(results_repo, monkeypatch):
    def no_git(*args, **kwargs):
        raise FileNotFoundError("git")

    monkeypatch.setattr(provenance.subprocess, "run", no_git)
    with pytest.raises(ValueError, match="holdout"):
        guard_copy(results_repo / "A-summary.tsv")


def test_write_starts_the_file_with_its_header_and_appends_one_row_per_command(tmp_path):
    found = provenance.resolve("A", str(PLAN))
    provenance.write(tmp_path, "escalate", found)
    provenance.write(tmp_path, "score", found)
    lines = (tmp_path / "provenance.tsv").read_text().splitlines()
    assert lines[0] == "command\tholdout\tplan\tplan_sha256\tgit_commit\twritten_utc"
    assert [line.split("\t")[:5] for line in lines[1:]] == [
        [command, "A", found.plan, found.plan_sha256, found.git_commit] for command in ("escalate", "score")]
    assert all(re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", line.split("\t")[5]) for line in lines[1:])


def test_write_refuses_a_file_with_another_header(tmp_path):
    (tmp_path / "provenance.tsv").write_text("something\telse\n")
    with pytest.raises(ValueError, match="provenance.tsv does not start with"):
        provenance.write(tmp_path, "score", provenance.resolve("A", str(PLAN)))
    assert (tmp_path / "provenance.tsv").read_text() == "something\telse\n"
