from __future__ import annotations

import base64
import json
from dataclasses import asdict

from cryptography.fernet import Fernet, InvalidToken
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

from .config import VAULT_META_PATH, VAULT_PATH
from .models import CredentialRecord
from .utils import utc_now_iso


class VaultError(Exception):
    pass


class LocalEncryptedVault:
    def exists(self) -> bool:
        return VAULT_PATH.exists() and VAULT_META_PATH.exists()

    def initialize(self, master_password: str) -> None:
        if self.exists():
            raise VaultError("Vault already exists.")
        salt = base64.urlsafe_b64encode(self._random_bytes(16)).decode("ascii")
        meta = {
            "version": 1,
            "kdf": "scrypt",
            "salt": salt,
            "n": 2**15,
            "r": 8,
            "p": 1,
            "created_at": utc_now_iso(),
        }
        VAULT_META_PATH.write_text(json.dumps(meta, indent=2), encoding="utf-8")
        payload = {"records": [], "created_at": utc_now_iso(), "updated_at": utc_now_iso()}
        self._save_payload(payload, master_password)

    def list_records(self, master_password: str) -> list[CredentialRecord]:
        payload = self._load_payload(master_password)
        return [CredentialRecord.from_dict(item) for item in payload.get("records", [])]

    def get_record(self, master_password: str, record_id: str) -> CredentialRecord | None:
        for record in self.list_records(master_password):
            if record.record_id == record_id:
                return record
        return None

    def upsert_records(self, master_password: str, records: list[CredentialRecord]) -> int:
        payload = self._load_payload(master_password)
        current = {item["record_id"]: item for item in payload.get("records", [])}
        before = len(current)
        for record in records:
            item = asdict(record)
            item["updated_at"] = utc_now_iso()
            if record.record_id not in current:
                item["created_at"] = utc_now_iso()
            else:
                item["created_at"] = current[record.record_id].get("created_at", utc_now_iso())
            current[record.record_id] = item
        payload["records"] = list(current.values())
        payload["updated_at"] = utc_now_iso()
        self._save_payload(payload, master_password)
        return len(current) - before

    def replace_record(self, master_password: str, record: CredentialRecord) -> None:
        self.upsert_records(master_password, [record])

    def _load_payload(self, master_password: str) -> dict:
        if not self.exists():
            raise VaultError("Vault is not initialized. Run `credential-defense init` first.")
        meta = json.loads(VAULT_META_PATH.read_text(encoding="utf-8"))
        key = self._derive_key(master_password, meta)
        fernet = Fernet(key)
        encrypted_blob = VAULT_PATH.read_bytes()
        try:
            raw = fernet.decrypt(encrypted_blob)
        except InvalidToken as exc:
            raise VaultError("Invalid vault password.") from exc
        return json.loads(raw.decode("utf-8"))

    def _save_payload(self, payload: dict, master_password: str) -> None:
        meta = json.loads(VAULT_META_PATH.read_text(encoding="utf-8"))
        key = self._derive_key(master_password, meta)
        fernet = Fernet(key)
        encoded = json.dumps(payload, indent=2).encode("utf-8")
        VAULT_PATH.write_bytes(fernet.encrypt(encoded))

    def _derive_key(self, master_password: str, meta: dict) -> bytes:
        kdf = Scrypt(
            salt=base64.urlsafe_b64decode(meta["salt"].encode("ascii")),
            length=32,
            n=int(meta.get("n", 2**15)),
            r=int(meta.get("r", 8)),
            p=int(meta.get("p", 1)),
        )
        key = kdf.derive(master_password.encode("utf-8"))
        return base64.urlsafe_b64encode(key)

    @staticmethod
    def _random_bytes(size: int) -> bytes:
        import os

        return os.urandom(size)

