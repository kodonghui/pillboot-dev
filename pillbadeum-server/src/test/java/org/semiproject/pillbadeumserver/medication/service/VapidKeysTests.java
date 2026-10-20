package org.semiproject.pillbadeumserver.medication.service;

import org.junit.jupiter.api.Test;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;

import java.security.KeyPair;

import static org.assertj.core.api.Assertions.*;

class VapidKeysTests {
    @Test
    void 짝이_맞는_키는_읽고_공개_키를_그대로_내준다() {
        KeyPair pair = PushTestKeys.generate();
        VapidKeys keys = new VapidKeys(PushTestKeys.publicText(pair), PushTestKeys.privateText(pair), "mailto:team@example.com");

        assertThat(keys.configured()).isTrue();
        assertThat(keys.publicKeyText()).isEqualTo(PushTestKeys.publicText(pair));
        assertThat(VapidKeys.raw(keys.publicKey())).hasSize(65).startsWith(0x04);
        assertThat(keys.privateKey().getS()).isEqualTo(((java.security.interfaces.ECPrivateKey) pair.getPrivate()).getS());
    }

    @Test
    void 키가_모두_비어_있으면_서버는_뜨고_푸시만_503이다() {
        VapidKeys keys = new VapidKeys("", "", "");
        assertThat(keys.configured()).isFalse();
        assertThatThrownBy(keys::publicKeyText).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PUSH_UNAVAILABLE));
    }

    @Test
    void 일부만_있거나_짝이_아니거나_형식이_틀리면_시작하지_않는다() {
        KeyPair pair = PushTestKeys.generate();
        KeyPair other = PushTestKeys.generate();
        String subject = "mailto:team@example.com";

        assertThatThrownBy(() -> new VapidKeys(PushTestKeys.publicText(pair), "", subject))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new VapidKeys(PushTestKeys.publicText(pair), PushTestKeys.privateText(other), subject))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("짝");
        assertThatThrownBy(() -> new VapidKeys(PushTestKeys.publicText(pair), PushTestKeys.privateText(pair), "team@example.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new VapidKeys("not+base64", PushTestKeys.privateText(pair), subject))
                .isInstanceOf(RuntimeException.class);
    }
}
