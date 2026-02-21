from __future__ import annotations


def prompt_yes_no(question: str, default: bool | None = None) -> bool:
    suffix = " [y/n]: "
    if default is True:
        suffix = " [Y/n]: "
    elif default is False:
        suffix = " [y/N]: "
    while True:
        response = input(question + suffix).strip().lower()
        if not response and default is not None:
            return default
        if response in {"y", "yes"}:
            return True
        if response in {"n", "no"}:
            return False
        print("Please answer y or n.")


def prompt_choice(question: str, options: list[str], default_index: int = 0) -> str:
    if not options:
        raise ValueError("options must not be empty")
    for idx, option in enumerate(options, start=1):
        marker = " (default)" if idx - 1 == default_index else ""
        print(f"{idx}. {option}{marker}")
    while True:
        raw = input(f"{question} [1-{len(options)}]: ").strip()
        if not raw:
            return options[default_index]
        if raw.isdigit():
            selected = int(raw)
            if 1 <= selected <= len(options):
                return options[selected - 1]
        print("Invalid selection.")

