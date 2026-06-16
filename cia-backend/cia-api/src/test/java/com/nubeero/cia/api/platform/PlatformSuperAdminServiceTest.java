package com.nubeero.cia.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nubeero.cia.api.platform.dto.InviteSuperAdminRequest;
import com.nubeero.cia.auth.PlatformRealmProperties;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner.SuperAdminView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.ObjectProvider;

class PlatformSuperAdminServiceTest {

    private Keycloak keycloak;
    private ObjectProvider<Keycloak> keycloakProvider;
    private KeycloakTenantProvisioner provisioner;
    private PlatformAuditService audit;
    private PlatformSuperAdminService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        keycloak = mock(Keycloak.class);
        keycloakProvider = mock(ObjectProvider.class);
        when(keycloakProvider.getIfAvailable()).thenReturn(keycloak);
        provisioner = mock(KeycloakTenantProvisioner.class);
        audit = mock(PlatformAuditService.class);
        var props = new PlatformRealmProperties(); // realm defaults to "platform"
        service = new PlatformSuperAdminService(keycloakProvider, Optional.of(provisioner), audit, props);
    }

    @Test
    void invite_createsAndAudits() {
        var resp = service.invite(new InviteSuperAdminRequest("sa2", "sa2@x.test"),
                "rootadmin", "platform", "1.1.1.1");

        assertThat(resp.username()).isEqualTo("sa2");
        assertThat(resp.temporaryPassword()).startsWith("Aa1!");
        verify(provisioner).createSuperAdmin(eq(keycloak), eq("platform"), eq("sa2"), eq("sa2@x.test"), anyString());
        verify(audit).record(eq("INVITE_SUPER_ADMIN"), eq(null), eq("rootadmin"), eq("platform"), anyString(), eq("1.1.1.1"));
    }

    @Test
    void invite_duplicate_mapsTo409() {
        org.mockito.Mockito.doThrow(new KeycloakTenantProvisioner.SuperAdminExistsInRealm("sa2"))
                .when(provisioner).createSuperAdmin(any(), anyString(), eq("sa2"), anyString(), anyString());

        assertThatThrownBy(() -> service.invite(new InviteSuperAdminRequest("sa2", "x@y.test"),
                "rootadmin", "platform", "1.1.1.1"))
                .isInstanceOf(SuperAdminExceptions.AlreadyExists.class);
        verify(audit, never()).record(anyString(), any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void revoke_self_blocked() {
        assertThatThrownBy(() -> service.revoke("rootadmin", "rootadmin", "platform", "1.1.1.1"))
                .isInstanceOf(SuperAdminExceptions.CannotRevokeSelf.class);
        verify(provisioner, never()).removeSuperAdminRole(any(), anyString(), anyString());
    }

    @Test
    void revoke_lastSuperAdmin_blocked() {
        when(provisioner.listSuperAdmins(keycloak, "platform"))
                .thenReturn(List.of(new SuperAdminView("victim", "v@x.test", true)));

        assertThatThrownBy(() -> service.revoke("victim", "rootadmin", "platform", "1.1.1.1"))
                .isInstanceOf(SuperAdminExceptions.CannotRevokeLast.class);
        verify(provisioner, never()).removeSuperAdminRole(any(), anyString(), anyString());
    }

    @Test
    void revoke_notFound_mapsTo404() {
        when(provisioner.listSuperAdmins(keycloak, "platform"))
                .thenReturn(List.of(new SuperAdminView("someoneelse", "s@x.test", true),
                                    new SuperAdminView("rootadmin", "r@x.test", true)));

        assertThatThrownBy(() -> service.revoke("ghost", "rootadmin", "platform", "1.1.1.1"))
                .isInstanceOf(SuperAdminExceptions.NotFound.class);
    }

    @Test
    void revoke_happyPath_removesAndAudits() {
        when(provisioner.listSuperAdmins(keycloak, "platform"))
                .thenReturn(List.of(new SuperAdminView("victim", "v@x.test", true),
                                    new SuperAdminView("rootadmin", "r@x.test", true)));

        service.revoke("victim", "rootadmin", "platform", "1.1.1.1");

        verify(provisioner).removeSuperAdminRole(keycloak, "platform", "victim");
        verify(audit).record(eq("REVOKE_SUPER_ADMIN"), eq(null), eq("rootadmin"), eq("platform"), anyString(), eq("1.1.1.1"));
    }

    @Test
    void adminDisabled_throws503Marker() {
        when(keycloakProvider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.list())
                .isInstanceOf(SuperAdminExceptions.KeycloakAdminDisabled.class);
    }

    // Regression guard for the bean-wiring fix: KeycloakTenantProvisioner is
    // @ConditionalOnProperty(cia.keycloak.admin.enabled=true), so it is absent (Optional.empty())
    // in dev / the IT suite. The service must still construct, and a method that needs the
    // provisioner must surface the 503 marker — not fail context startup with NoSuchBeanDefinition.
    @Test
    void provisionerAbsent_throws503Marker() {
        var noProvisioner = new PlatformSuperAdminService(
                keycloakProvider, Optional.empty(), audit, new PlatformRealmProperties());
        assertThatThrownBy(noProvisioner::list)
                .isInstanceOf(SuperAdminExceptions.KeycloakAdminDisabled.class);
    }
}
