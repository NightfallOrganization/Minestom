package net.minestom.server.permission;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface RedirectPermissionHandler extends PermissionHandler {
    /**
     * @return the permission handler that is used.
     */
    @ApiStatus.Internal
    @NotNull
    PermissionHandler getPermissionHandler();

    @Override
    default @NotNull Set<Permission> getAllPermissions() {
        return getPermissionHandler().getAllPermissions();
    }

    @Override
    default void addPermission(@NotNull Permission permission) {
        getPermissionHandler().addPermission(permission);
    }

    @Override
    default void removePermission(@NotNull Permission permission) {
        getPermissionHandler().removePermission(permission);
    }

    @Override
    default void removePermission(@NotNull String permissionName) {
        getPermissionHandler().removePermission(permissionName);
    }

    @Override
    default boolean hasPermission(@NotNull Permission permission) {
        return getPermissionHandler().hasPermission(permission);
    }

    @Override
    @Nullable
    default Permission getPermission(@NotNull String permissionName) {
        return getPermissionHandler().getPermission(permissionName);
    }

    @Override
    default boolean hasPermission(@NotNull String permissionName, @Nullable PermissionVerifier permissionVerifier) {
        return getPermissionHandler().hasPermission(permissionName, permissionVerifier);
    }

    @Override
    default boolean hasPermission(@NotNull String permissionName) {
        return getPermissionHandler().hasPermission(permissionName);
    }
}
