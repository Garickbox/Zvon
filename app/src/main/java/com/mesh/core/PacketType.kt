package com.mesh.core

enum class PacketType(val id: Byte) {
    HELLO(0x01),
    KEEPALIVE(0x02),
    VOICE(0x04),
    CALL_INVITE(0x05),
    CALL_ACCEPT(0x06),
    CALL_BYE(0x07),
    TEXT(0x08),
    FILE_OFFER(0x09),
    FILE_ACCEPT(0x0A),
    FILE_DECLINE(0x0B),
    TEST(0x20);

    companion object {
        fun fromId(id: Byte) = values().firstOrNull { it.id == id }
    }
}
