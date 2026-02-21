from __future__ import annotations

import secrets
import string


ALPHABET = (
    string.ascii_lowercase
    + string.ascii_uppercase
    + string.digits
    + "!@#$%^&*()-_=+[]{}:,.?"
)


def generate_strong_password(length: int = 24) -> str:
    if length < 16:
        raise ValueError("password length must be >= 16")
    return "".join(secrets.choice(ALPHABET) for _ in range(length))


def is_weak_password(password: str) -> bool:
    if len(password) < 14:
        return True
    checks = [
        any(c.islower() for c in password),
        any(c.isupper() for c in password),
        any(c.isdigit() for c in password),
        any(c in "!@#$%^&*()-_=+[]{}:,.?" for c in password),
    ]
    return not all(checks)

