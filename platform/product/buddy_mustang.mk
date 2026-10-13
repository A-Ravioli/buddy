# buddy build for the Pixel 10 Pro XL (mustang), the reference device.

# VERIFY after first sync: this must be the product makefile GrapheneOS builds for
# `mustang` at the pinned tag. It has historically been device/google/<codename>/<aosp_codename>.mk.
$(call inherit-product, device/google/mustang/aosp_mustang.mk)
$(call inherit-product, vendor/buddy/platform/product/buddy_common.mk)

PRODUCT_NAME := buddy_mustang
PRODUCT_DEVICE := mustang
PRODUCT_BRAND := buddy
PRODUCT_MODEL := Pixel 10 Pro XL
PRODUCT_MANUFACTURER := Google
