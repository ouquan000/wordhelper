"""手动重写在一次领取结束时决定是否继续质量退避。"""

QUALITY_ROUND_LIMIT = 5


def valid_candidate(approach):
    return (
        isinstance(approach, (list, tuple))
        and bool(approach)
        and all(isinstance(line, str) and line.strip() for line in approach)
    )


def rejection_state(attempts, failures, candidate):
    """failures 已含本次拒绝。任一持久化计数到顶即终结。"""
    if max(attempts, failures) < QUALITY_ROUND_LIMIT:
        return "retry_wait"
    return "failed"
