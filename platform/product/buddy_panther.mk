# buddy build for the Pixel 7 (panther), a development device.
#
# Tensor G2, so the audio pipeline's DSP hotword and on-device transcription budgets
# set on the Pixel 10 Pro XL do not carry over; battery numbers from this phone are not
# the budget. Everything else is the same build.

# VERIFY after first sync: the Pixel 7 and 7 Pro share one device tree, `pantah`, and the
# product GrapheneOS builds for `panther` has historically been device/google/pantah/aosp_panther.mk.
$(call inherit-product, device/google/pantah/aosp_panther.mk)
$(call inherit-product, vendor/buddy/platform/product/buddy_common.mk)

PRODUCT_NAME := buddy_panther
PRODUCT_DEVICE := panther
PRODUCT_BRAND := buddy
PRODUCT_MODEL := Pixel 7
PRODUCT_MANUFACTURER := Google
