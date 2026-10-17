package com.airbridge.touch.transport

class TransportStateMachine {
    var state: TransportState = TransportState.Idle
        private set

    fun transition(next: TransportState): Boolean {
        val allowed = when (val current = state) {
            TransportState.Idle -> next is TransportState.Discovering || next is TransportState.Unavailable || next is TransportState.Failed
            is TransportState.Unavailable -> next is TransportState.Idle || next is TransportState.Discovering || next is TransportState.Failed
            TransportState.Discovering -> next is TransportState.PeersAvailable || next is TransportState.Idle || next is TransportState.Failed || next is TransportState.Unavailable
            is TransportState.PeersAvailable -> next is TransportState.Connecting || next is TransportState.Discovering || next is TransportState.Idle || next is TransportState.Failed
            is TransportState.Connecting -> next is TransportState.Connected || next is TransportState.Failed || next is TransportState.Idle
            is TransportState.Connected -> next is TransportState.Idle || next is TransportState.Failed
            is TransportState.Failed -> next is TransportState.Idle || next is TransportState.Discovering
        }
        if (allowed) state = next
        return allowed
    }
}
