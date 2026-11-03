package com.example.niksey.utillits.signal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.util.UUID

/**
 * Sender Keys (libsignal): участник получает SKDM отправителя и может читать его сообщения группы.
 * Участник без SKDM — не может. Так работает SignalManager.syncGroupKeys / encryptForGroup.
 */
class GroupFlowTest {

    private fun store(name: String) = InMemorySignalProtocolStore(IdentityKeyPair.generate(), name.length)

    @Test
    fun memberWithSenderKeyReadsGroupMessages() {
        val alice = store("alice")
        val bob = store("bobby")
        val charlie = store("charlie")
        val aliceAddress = SignalProtocolAddress("alice", 1)
        val distribution = UUID.nameUUIDFromBytes("niksey-group:g1".toByteArray())

        // Алиса создаёт свой sender key и отправляет SKDM Бобу (в приложении — через личную сессию)
        val skdm = GroupSessionBuilder(alice).create(aliceAddress, distribution)
        GroupSessionBuilder(bob).process(aliceAddress, SenderKeyDistributionMessage(skdm.serialize()))

        val first = GroupCipher(alice, aliceAddress).encrypt(distribution, "первое".toByteArray())
        val second = GroupCipher(alice, aliceAddress).encrypt(distribution, "второе".toByteArray())

        assertArrayEquals("первое".toByteArray(), GroupCipher(bob, aliceAddress).decrypt(first.serialize()))
        assertArrayEquals("второе".toByteArray(), GroupCipher(bob, aliceAddress).decrypt(second.serialize()))
    }

    @Test
    fun memberWithoutSenderKeyCannotRead() {
        val alice = store("alice")
        val charlie = store("charlie")
        val aliceAddress = SignalProtocolAddress("alice", 1)
        val distribution = UUID.nameUUIDFromBytes("niksey-group:g1".toByteArray())

        GroupSessionBuilder(alice).create(aliceAddress, distribution)
        val message = GroupCipher(alice, aliceAddress).encrypt(distribution, "секрет".toByteArray())

        assertThrows(Exception::class.java) {
            GroupCipher(charlie, aliceAddress).decrypt(message.serialize())
        }
    }
}
