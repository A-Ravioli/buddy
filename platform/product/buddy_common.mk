# buddy on top of a GrapheneOS device product. Every device makefile (buddy_<codename>.mk)
# inherits its GrapheneOS product first and then this, so what buddy adds is written once:
# the system app, its permission grants, the framework overlay that hands it the roles,
# the boot animation, and the build identity. Nothing GrapheneOS ships is removed here;
# the launcher and other viewer-assuming chrome go in Phase 4.

# The buddy system app and its configuration.
PRODUCT_PACKAGES += \
    Buddy \
    privapp-permissions-buddy.xml \
    default-permissions-buddy.xml \
    sysconfig-buddy.xml

# Framework overlay: default roles, content capture service, listener access.
PRODUCT_PACKAGE_OVERLAYS += vendor/buddy/platform/overlay

# The boot animation: buddy opening his eyes, drawn from the same geometry the app and the
# lock screen use and packed by :platform:bootanimation. scripts/build.sh writes it into
# the tree before the platform build; a tree without it falls back to the base animation
# rather than failing, and says so.
# VERIFY at the pinned tag: surfaceflinger reads /product/media first, then /system/media.
BUDDY_BOOTANIMATION := vendor/buddy/platform/bootanimation/build/bootanimation.zip
ifneq ($(wildcard $(BUDDY_BOOTANIMATION)),)
PRODUCT_COPY_FILES += $(BUDDY_BOOTANIMATION):$(TARGET_COPY_OUT_PRODUCT)/media/bootanimation.zip
else
$(warning buddy: no bootanimation.zip; run ./gradlew :platform:bootanimation:bootAnimation)
endif

# buddy's own build identity, readable from the app and the timeline.
PRODUCT_SYSTEM_EXT_PROPERTIES += \
    ro.buddy.build.tag=$(BUDDY_BASE_TAG) \
    ro.buddy.build.phase=0

# Persistent app: buddy's process is kept alive by the system like a system service.
# The manifest sets android:persistent; this property is what the framework honours
# for system_ext apps on this release. VERIFY the exact mechanism at the pinned tag.
PRODUCT_SYSTEM_EXT_PROPERTIES += persist.buddy.persistent=1
