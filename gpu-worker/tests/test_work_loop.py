from argparse import Namespace
from contextlib import redirect_stderr
from io import StringIO
import signal
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from bangpick_worker import __main__ as cli
from bangpick_worker.__main__ import ProcessNextResult, ReportedJobFailure, work_loop
from bangpick_worker.transfers import TransferError


class WorkLoopTests(unittest.TestCase):
    def test_empty_queue_waits_but_completed_job_polls_immediately(self):
        with patch.object(cli, "process_next", side_effect=[
            ProcessNextResult.NO_JOB, ProcessNextResult.COMPLETED, KeyboardInterrupt,
        ]) as process, patch.object(cli.time, "sleep") as sleep:
            with self.assertRaises(KeyboardInterrupt):
                work_loop(Namespace(), object())
        self.assertEqual(process.call_count, 3)
        sleep.assert_called_once_with(5)

    def test_confirmed_retry_and_terminal_failures_allow_next_claim(self):
        errors = [
            ReportedJobFailure("job-1", "QUEUED", "input unavailable"),
            ReportedJobFailure("job-2", "FAILED", "processor failed"),
        ]
        output = StringIO()
        with patch.object(cli, "process_next", side_effect=[*errors, KeyboardInterrupt]) as process, \
                patch.object(cli.time, "sleep") as sleep, redirect_stderr(output):
            with self.assertRaises(KeyboardInterrupt):
                work_loop(Namespace(), object())
        self.assertEqual(process.call_count, 3)
        sleep.assert_not_called()
        self.assertIn("job-1 is QUEUED", output.getvalue())
        self.assertIn("job-2 is FAILED", output.getvalue())

    def test_uncertain_attempt_stops_without_claiming_again(self):
        for message in ("Claim response unavailable", "Heartbeat unavailable",
                        "The backend did not confirm the failure", "Completion unavailable"):
            with self.subTest(message=message):
                with patch.object(cli, "process_next", side_effect=TransferError(message)) as process, \
                        patch.object(cli.time, "sleep") as sleep:
                    with self.assertRaisesRegex(TransferError, message):
                        work_loop(Namespace(), object())
                process.assert_called_once()
                sleep.assert_not_called()

    def test_sigterm_stops_loop_and_restores_previous_handler(self):
        previous_handler = signal.getsignal(signal.SIGTERM)
        with patch.object(cli, "process_next", side_effect=lambda *_: signal.raise_signal(signal.SIGTERM)) as process:
            with self.assertRaises(cli.TerminationRequested):
                work_loop(Namespace(), object())
        process.assert_called_once()
        self.assertEqual(signal.getsignal(signal.SIGTERM), previous_handler)

    def test_cli_passes_processor_and_retry_flag_to_loop(self):
        with TemporaryDirectory() as directory:
            argv = ["bangpick-worker", "work-loop", "--work-dir", directory, "--retryable",
                    "--", sys.executable, "processor.py"]
            with patch.object(sys, "argv", argv), patch.object(cli, "Transfers", return_value=object()), \
                    patch.object(cli, "work_loop", side_effect=KeyboardInterrupt) as loop:
                with self.assertRaises(SystemExit) as caught:
                    cli.main()
            self.assertEqual(caught.exception.code, 130)
            args = loop.call_args.args[0]
            self.assertEqual(args.action, "work-loop")
            self.assertEqual(args.processor, [sys.executable, "processor.py"])
            self.assertTrue(args.retryable)


if __name__ == "__main__":
    unittest.main()
