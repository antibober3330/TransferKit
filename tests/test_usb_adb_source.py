import unittest
from uuid import UUID
from unittest.mock import patch

from transferkit.transport.usb_adb import (
    AUTH_RESULT_STRUCT,
    CHALLENGE_STRUCT,
    CONTROL_VERSION,
    OFFER_ACK_STRUCT,
    OFFER_STRUCT,
    PROOF_STRUCT,
    SOURCE_IDENTITY_STRUCT,
    TransportError,
    UsbAdbSourceTransport,
    usb_adb_auth_proof,
)


TRANSFER_ID = UUID("00000000-0000-4000-8000-000000000001")
NONCE = bytes(range(32))


class ScriptedSocket:
    def __init__(self, incoming=b""):
        self.incoming = bytearray(incoming)
        self.outgoing = bytearray()
        self.timeout = None
        self.closed = False

    def recv(self, count):
        data = bytes(self.incoming[:count])
        del self.incoming[:count]
        return data

    def sendall(self, data):
        self.outgoing.extend(data)

    def settimeout(self, timeout):
        self.timeout = timeout

    def shutdown(self, _how):
        pass

    def close(self):
        self.closed = True


class FakeAdb:
    def __init__(self):
        self.forwards = []
        self.removed = []

    def create_forward(self, serial, abstract_socket):
        self.forwards.append((serial, abstract_socket))
        return 43100 + len(self.forwards)

    def remove_forward(self, serial, port):
        self.removed.append((serial, port))


class UsbAdbSourceTransportTests(unittest.TestCase):
    def setUp(self):
        self.offer_ack = OFFER_ACK_STRUCT.pack(b"TKOA", CONTROL_VERSION, 0, TRANSFER_ID.bytes)
        self.source_identity = SOURCE_IDENTITY_STRUCT.pack(b"TKSI", CONTROL_VERSION, TRANSFER_ID.bytes)
        self.challenge = CHALLENGE_STRUCT.pack(b"TKHC", CONTROL_VERSION, TRANSFER_ID.bytes, NONCE)
        self.auth_result = AUTH_RESULT_STRUCT.pack(b"TKOK", CONTROL_VERSION, 0, TRANSFER_ID.bytes)

    def test_source_transport_reads_identity_then_completes_authenticated_handshake(self):
        data = ScriptedSocket(self.source_identity + self.challenge + self.auth_result)
        control = ScriptedSocket(self.offer_ack)
        adb = FakeAdb()
        with patch("transferkit.transport.usb_adb.socket.create_connection", side_effect=[data, control]):
            source = UsbAdbSourceTransport(adb, "SOURCE-USB")
            self.assertEqual(source.open(), TRANSFER_ID)
            magic, version, offered_id, token = OFFER_STRUCT.unpack(control.outgoing)
            self.assertEqual((magic, version, offered_id), (b"TKSO", CONTROL_VERSION, TRANSFER_ID.bytes))
            proof_magic, proof_version, proof = PROOF_STRUCT.unpack(data.outgoing)
            self.assertEqual((proof_magic, proof_version), (b"TKHA", CONTROL_VERSION))
            self.assertEqual(proof, usb_adb_auth_proof(TRANSFER_ID, token, NONCE))
            self.assertIs(source.connection, data)
            source.close()

        self.assertTrue(data.closed)
        self.assertTrue(control.closed)
        self.assertEqual(adb.removed, [("SOURCE-USB", 43101), ("SOURCE-USB", 43102)])

    def test_rejected_source_offer_closes_forwards_and_reports_reason(self):
        rejected = OFFER_ACK_STRUCT.pack(b"TKOA", CONTROL_VERSION, 1, TRANSFER_ID.bytes)
        data = ScriptedSocket(self.source_identity)
        control = ScriptedSocket(rejected)
        adb = FakeAdb()
        with patch("transferkit.transport.usb_adb.socket.create_connection", side_effect=[data, control]):
            source = UsbAdbSourceTransport(adb, "SOURCE-USB")
            with self.assertRaises(TransportError) as caught:
                source.open()

        self.assertEqual(caught.exception.reason.value, "INVALID_MANIFEST")
        self.assertTrue(data.closed)
        self.assertTrue(control.closed)
        self.assertEqual(len(adb.removed), 2)


if __name__ == "__main__":
    unittest.main()
