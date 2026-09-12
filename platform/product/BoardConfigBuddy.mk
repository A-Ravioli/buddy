# Included from the device BoardConfig by patch 0007 (see patches/README.md):
#   -include vendor/buddy/platform/product/BoardConfigBuddy.mk
#
# Adds buddy's SELinux policy to the system_ext policy. Phase 0 only maps the app to
# an existing domain; the per-subsystem domains in sepolicy/draft are not yet built.
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/buddy/platform/sepolicy/private
