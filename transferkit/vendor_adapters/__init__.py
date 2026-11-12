"""Capability-only OEM adapter contracts and selection."""

from .base import GenericAndroidAdapter, VendorAdapter, VendorAdapterRegistry
from .catalog import (
    CatalogVendorAdapter,
    GooglePixelAdapter,
    HonorAdapter,
    HuaweiAdapter,
    InfinixAdapter,
    MotorolaAdapter,
    OnePlusAdapter,
    OppoAdapter,
    RealmeAdapter,
    SamsungAdapter,
    TecnoAdapter,
    VivoAdapter,
    ZTEAdapter,
    ZteAdapter,
    default_vendor_adapters,
)

DEFAULT_VENDOR_ADAPTERS = VendorAdapterRegistry(default_vendor_adapters())

__all__ = [
    "CatalogVendorAdapter",
    "DEFAULT_VENDOR_ADAPTERS",
    "GenericAndroidAdapter",
    "GooglePixelAdapter",
    "HonorAdapter",
    "HuaweiAdapter",
    "InfinixAdapter",
    "MotorolaAdapter",
    "OnePlusAdapter",
    "OppoAdapter",
    "RealmeAdapter",
    "SamsungAdapter",
    "TecnoAdapter",
    "VendorAdapter",
    "VendorAdapterRegistry",
    "VivoAdapter",
    "ZTEAdapter",
    "ZteAdapter",
    "default_vendor_adapters",
]
