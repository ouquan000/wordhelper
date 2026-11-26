"""Validate the optional ordered Android queue preview before changing sync state."""


def parse_upcoming(body):
    if "upcoming_new_word_ids" not in body:
        return {}
    ids = body["upcoming_new_word_ids"]
    if not isinstance(ids, list) or len(ids) > 50 or any(
        type(i) is not int or not 0 < i < 2**63 for i in ids
    ):
        raise ValueError("upcoming_new_word_ids requires up to 50 positive Long IDs")
    keys = body.get("upcoming_new_word_keys")
    if keys is not None and (not isinstance(keys, list) or len(keys) != len(ids)
            or any(not isinstance(k, str) or not k.strip() for k in keys)):
        raise ValueError("upcoming_new_word_keys must correspond to the ID list")
    book = body.get("upcoming_new_book")
    book_id = body.get("upcoming_new_book_id")
    if book is not None and not isinstance(book, str):
        raise ValueError("invalid upcoming_new_book")
    if book_id is not None and (type(book_id) is not int or not 0 < book_id < 2**63):
        raise ValueError("invalid upcoming_new_book_id")
    return {"upcoming": {"upcoming_new_word_ids": ids, "upcoming_new_word_keys": keys,
                         "upcoming_new_book": book, "upcoming_new_book_id": book_id}}
