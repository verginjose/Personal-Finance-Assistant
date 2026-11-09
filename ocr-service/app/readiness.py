"""Dependency readiness without loading the OCR/CUDA stack."""


def consumer_ready(connection, task, subscribed):
    return bool(
        subscribed
        and task is not None
        and not task.done()
        and connection is not None
        and not connection.is_closed
        and connection.connected.is_set()
    )
