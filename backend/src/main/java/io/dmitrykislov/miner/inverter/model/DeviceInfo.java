package io.dmitrykislov.miner.inverter.model;

/** Identity of a device returned by the {@code devicelist} service. */
public record DeviceInfo(int devId, int devType, String model, String serialNumber) {}
