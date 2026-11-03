package com.example.niksey.utillits.signal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.IdentityKey

/**
 * Проверяем сам протокол Signal (libsignal, нативный код на JVM): PQXDH-сессия, Double Ratchet,
 * защита от повтора. Логика повторяет SignalManager, но на in-memory хранилищах.
 */
class SignalFlowTest {

    private val deviceId = 1

    private class Party(val name: String) {
        val identity: IdentityKeyPair = IdentityKeyPair.generate()
        val store = InMemorySignalProtocolStore(identity, 1234 + name.length)
        val address = SignalProtocolAddress(name, 1)
    }

    /** Публикуем набор ключей Боба точно так, как SignalManager.publishBundle. */
    private fun bobBundle(bob: Party): PreKeyBundle {
        val now = System.currentTimeMillis()
        val signed = ECKeyPair.generate()
        bob.store.storeSignedPreKey(
            1,
            SignedPreKeyRecord(1, now, signed, bob.identity.privateKey.calculateSignature(signed.publicKey.serialize()))
        )
        val kem = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        bob.store.storeKyberPreKey(
            1,
            KyberPreKeyRecord(1, now, kem, bob.identity.privateKey.calculateSignature(kem.publicKey.serialize()))
        )
        val oneTime = ECKeyPair.generate()
        bob.store.storePreKey(7, PreKeyRecord(7, oneTime))

        return PreKeyBundle(
            bob.store.localRegistrationId, deviceId,
            7, oneTime.publicKey,
            1, signed.publicKey, bob.identity.privateKey.calculateSignature(signed.publicKey.serialize()),
            bob.identity.publicKey,
            1, kem.publicKey, bob.identity.privateKey.calculateSignature(kem.publicKey.serialize())
        )
    }

    @Test
    fun sessionEstablishesAndMessagesRoundTrip() {
        val alice = Party("alice")
        val bob = Party("bob")

        // Alice получает набор ключей Боба и создаёт сессию
        SessionBuilder(alice.store, bob.address).process(bobBundle(bob))

        // Первое сообщение — PreKey (начало сессии)
        val first = SessionCipher(alice.store, bob.address).encrypt("привет, Боб".toByteArray())
        assertEquals(CiphertextMessage.PREKEY_TYPE, first.type)

        // Боб расшифровывает и сам создаёт сессию из первого сообщения
        val received = SessionCipher(bob.store, alice.address)
            .decrypt(PreKeySignalMessage(first.serialize()))
        assertArrayEquals("привет, Боб".toByteArray(), received)

        // Ответ Боба — обычное сообщение Double Ratchet
        val reply = SessionCipher(bob.store, alice.address).encrypt("ответ".toByteArray())
        assertEquals(CiphertextMessage.WHISPER_TYPE, reply.type)
        val replyPlain = SessionCipher(alice.store, bob.address).decrypt(SignalMessage(reply.serialize()))
        assertArrayEquals("ответ".toByteArray(), replyPlain)

        // Ключи меняются от сообщения к сообщению (Double Ratchet): шифротексты разные
        val second = SessionCipher(alice.store, bob.address).encrypt("два".toByteArray())
        val third = SessionCipher(alice.store, bob.address).encrypt("два".toByteArray())
        assertTrue(!second.serialize().contentEquals(third.serialize()))
    }

    @Test
    fun replayOfSameMessageIsRejected() {
        val alice = Party("alice")
        val bob = Party("bob")
        SessionBuilder(alice.store, bob.address).process(bobBundle(bob))
        val msg = SessionCipher(alice.store, bob.address).encrypt("один раз".toByteArray())

        SessionCipher(bob.store, alice.address).decrypt(PreKeySignalMessage(msg.serialize()))

        // Повторная расшифровка того же сообщения невозможна: ключ сообщения уже использован
        assertThrows(Exception::class.java) {
            SessionCipher(bob.store, alice.address).decrypt(PreKeySignalMessage(msg.serialize()))
        }
    }

    @Test
    fun kyberPreKeyIsPartOfTheHandshake() {
        // Меняем подпись Kyber-ключа: инициатор обязан отвергнуть такой набор
        val alice = Party("alice")
        val bob = Party("bob")
        val good = bobBundle(bob)
        val forged = PreKeyBundle(
            good.registrationId, deviceId,
            good.preKeyId, good.preKey,
            good.signedPreKeyId, good.signedPreKey, good.signedPreKeySignature,
            good.identityKey,
            good.kyberPreKeyId, good.kyberPreKey, ByteArray(64)
        )
        assertThrows(Exception::class.java) {
            SessionBuilder(alice.store, bob.address).process(forged)
        }
    }

    @Test
    fun trustOnFirstUseDetectsIdentityChange() {
        val alice = Party("alice")
        val bob = Party("bob")
        val bobKey: IdentityKey = bob.identity.publicKey
        alice.store.saveIdentity(bob.address, bobKey)
        assertTrue(alice.store.isTrustedIdentity(bob.address, bobKey, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.RECEIVING))

        val impostor = Party("impostor").identity.publicKey
        assertTrue(!alice.store.isTrustedIdentity(bob.address, impostor, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.RECEIVING))
    }
}
