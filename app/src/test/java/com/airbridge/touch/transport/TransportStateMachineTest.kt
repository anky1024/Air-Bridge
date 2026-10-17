package com.airbridge.touch.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportStateMachineTest {
    @Test
    fun allowsNormalDiscoveryConnectionDisconnectPath() {
        val machine = TransportStateMachine()
        assertTrue(machine.transition(TransportState.Discovering))
        assertTrue(machine.transition(TransportState.PeersAvailable(listOf(PeerDevice("1", "Phone", "aa", "Available")))))
        assertTrue(machine.transition(TransportState.Connecting(PeerDevice("1", "Phone", "aa", "Available"))))
        assertTrue(machine.transition(TransportState.Connected("Phone", false, "192.168.49.1")))
        assertTrue(machine.transition(TransportState.Idle))
    }

    @Test
    fun preventsDuplicateConnectionFromConnectedState() {
        val machine = TransportStateMachine()
        assertTrue(machine.transition(TransportState.Discovering))
        assertTrue(machine.transition(TransportState.PeersAvailable(listOf(PeerDevice("1", "Phone", "aa", "Available")))))
        assertTrue(machine.transition(TransportState.Connecting(PeerDevice("1", "Phone", "aa", "Available"))))
        assertTrue(machine.transition(TransportState.Connected("Phone", true, null)))
        assertFalse(machine.transition(TransportState.Connecting(PeerDevice("2", "Other", "bb", "Available"))))
    }
}
