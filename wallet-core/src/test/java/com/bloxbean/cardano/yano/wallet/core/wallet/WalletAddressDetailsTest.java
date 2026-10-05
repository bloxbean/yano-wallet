package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.bip32.HdKeyGenerator;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.hdwallet.Wallet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class WalletAddressDetailsTest {
    private static final String MNEMONIC = "drive useless envelope shine range ability time copper alarm museum near flee wrist "
            + "live type device meadow allow churn purity wisdom praise drop code";

    @Test void softwareAndHardwareDetailsMatchTheirAddressWithoutExposingExtendedKeys() {
        Wallet wallet = Wallet.createFromMnemonic(Networks.preprod(), MNEMONIC, 2);
        var generator = new HdKeyGenerator();
        var purpose = generator.getChildKeyPair(wallet.getRootKeyPair().orElseThrow(), 1852, true);
        var coin = generator.getChildKeyPair(purpose, 1815, true);
        var account = generator.getChildKeyPair(coin, 2, true);
        var watch = new WatchOnlyWallet(Networks.preprod(), account.getPublicKey().getBytes(), 2);
        var service = new WalletAddressService();
        for (int index : new int[]{0, 4, 21}) {
            var details = service.addressDetails(wallet, index);
            assertThat(service.addressDetails(watch, index)).isEqualTo(details);
            assertThat(details.paymentPublicKey()).hasSize(64);
            assertThat(details.stakePublicKey()).hasSize(64);
            assertThat(details.paymentPath()).isEqualTo("m/1852'/1815'/2'/0/" + index);
            assertThat(details.stakePath()).isEqualTo("m/1852'/1815'/2'/2/0");
            var address = new Address(details.address());
            assertThat(HexUtil.encodeHexString(address.getPaymentCredentialHash().orElseThrow()))
                    .isEqualTo(details.paymentKeyHash());
            assertThat(HexUtil.encodeHexString(address.getDelegationCredentialHash().orElseThrow()))
                    .isEqualTo(details.stakeKeyHash());
            assertThat(HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(HexUtil.decodeHexString(details.paymentPublicKey()))))
                    .isEqualTo(details.paymentKeyHash());
            assertThat(HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(HexUtil.decodeHexString(details.stakePublicKey()))))
                    .isEqualTo(details.stakeKeyHash());
        }
        assertThat(service.addressDetails(wallet, 0).paymentPublicKey())
                .isNotEqualTo(service.addressDetails(wallet, 4).paymentPublicKey());
        assertThat(service.addressDetails(wallet, 0).stakePublicKey())
                .isEqualTo(service.addressDetails(wallet, 4).stakePublicKey());
        assertThatThrownBy(() -> service.addressDetails(wallet, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
