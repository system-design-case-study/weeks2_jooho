package com.nearbyfreinds.hash;

public record VirtualNodeInfo<T>(long position, T physicalNode, int virtualIndex) {
}
