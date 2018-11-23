package com.guarda.ethereum.managers;

import android.content.Context;
import android.net.Credentials;
import android.util.Log;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.firebase.crash.FirebaseCrash;
import com.guarda.ethereum.GuardaApp;
import com.guarda.ethereum.models.items.UTXOItem;
import com.guarda.ethereum.rest.ApiMethods;
import com.guarda.ethereum.rest.RequestorBtc;
import com.guarda.ethereum.utils.Coders;
import com.guarda.ethereum.utils.FileUtils;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.CashAddressFactory;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.core.UTXOProvider;
import org.bitcoinj.core.UTXOProviderException;
import org.bitcoinj.core.WrongNetworkException;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.spongycastle.util.encoders.Hex;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import javax.inject.Inject;

import autodagger.AutoInjector;

import static com.guarda.ethereum.models.constants.Common.BIP_39_WORDLIST_ASSET;
import static com.guarda.ethereum.models.constants.Common.MNEMONIC_WORDS_COUNT;
import static com.guarda.ethereum.models.constants.Const.LOG_TAG;


/**
 * Provide all actions with Bitcoin wallet
 * <p>
 * NOTE: not recommend use native function of bitcoinj library, like wallet.getBalance()
 * because, this wallet restoring using list of utxo and this mean that bitcoinj library is not approve
 * method getBalance, because it will try to check is utxo is valid
 * 09.10.2017.
 */

@AutoInjector(GuardaApp.class)
public class WalletManager {

    private Wallet wallet;
    private String walletFriendlyAddress;

    @Inject
    SharedManager sharedManager;


    private Coin myBalance;
    private Context context;
    private static NetworkParameters params = MainNetParams.get();
    private String mnemonicKey;
    private String wifKey;
    private String xprvKey;
    private String privhex;
    private HashSet<String> mBip39Words;
    private boolean restFromWif = false;
    private BigDecimal balance = BigDecimal.ZERO;


    public WalletManager(Context context) {
        GuardaApp.getAppComponent().inject(this);
        this.context = context;
        mBip39Words = FileUtils.readToSet(context, BIP_39_WORDLIST_ASSET);
    }

    public boolean isCorrectMnemonic(String mnemonic) {
        String[] words = mnemonic.split("\\W+");
        for (String word : words) {
            if (!mBip39Words.contains(word)) {
                return false;
            }
        }
        return true;
    }

    public void createWallet(String passphrase, WalletCreationCallback callback) {
        restoreFromBlock(Coders.decodeBase64(sharedManager.getLastSyncedBlock()), callback);
    }

    public void createWallet2(String passphrase, Runnable callback) {

        wallet = new Wallet(params);
        DeterministicSeed seed = wallet.getKeyChainSeed();

        mnemonicKey = Joiner.on(" ").join(seed.getMnemonicCode());
        sharedManager.setLastSyncedBlock(Coders.encodeBase64(mnemonicKey));

        walletFriendlyAddress = wallet.currentReceiveAddress().toString();

        ChildNumber childNumber = new ChildNumber(ChildNumber.HARDENED_BIT);
        DeterministicKey de = wallet.getKeyByPath(ImmutableList.of(childNumber));
        xprvKey = de.serializePrivB58(params);

        wifKey = wallet.currentReceiveKey().getPrivateKeyAsWiF(params);

        callback.run();
    }

    public void restoreFromBlock(String mnemonicCode, WalletCreationCallback callback) {
        mnemonicCode = mnemonicCode.trim();
        if (mnemonicCode.equals("")) {
            callback.onWalletCreated(null);
            return;
        }

        DeterministicSeed seed = new DeterministicSeed(Splitter.on(' ').splitToList(mnemonicCode), null, "", 0);

        wallet = Wallet.fromSeed(params, seed);
        mnemonicKey = Joiner.on(" ").join(seed.getMnemonicCode());
        //sharedManager.setLastSyncedBlock(Coders.encodeBase64(mnemonicKey));

        walletFriendlyAddress = wallet.currentReceiveAddress().toString();

        ChildNumber childNumber = new ChildNumber(ChildNumber.HARDENED_BIT);
        DeterministicKey de = wallet.getKeyByPath(ImmutableList.of(childNumber));
        xprvKey = de.serializePrivB58(params);

        wifKey = wallet.currentReceiveKey().getPrivateKeyAsWiF(params);

        callback.onWalletCreated(wallet);

        requestUnspents();
    }

    public void restoreFromBlockByXPRV(String xprv, WalletCreationCallback callback) {
        xprv = xprv.trim();
        try {
            DeterministicKey dk01 = DeterministicKey.deserializeB58(xprv, params);
            DeterministicHierarchy dh = new DeterministicHierarchy(dk01);
            List<ChildNumber> chns = ImmutableList.of(new ChildNumber(ChildNumber.HARDENED_BIT), new ChildNumber(0, false));
            DeterministicKey dk03 = dh.deriveChild(chns, false, true, new ChildNumber(0, false));
            xprvKey = xprv;
            Log.d(LOG_TAG, "m/0'/0=" + dk03.getPrivateKeyAsWiF(params) + " privkey=" + dk03.getPrivateKeyAsHex());
            restoreFromBlockByWif(dk03.getPrivateKeyAsWiF(params), callback);
//            byte[] phx = Utils.HEX.decode(dk03.getPrivateKeyAsHex());
//            BigInteger bi = new BigInteger(phx);
//            ECKey preck = ECKey.fromPrivate(phx);
//            Wallet wallet = new Wallet(params);
//            wallet.importKey(preck);
//            String wfa = wallet.getImportedKeys().get(0).toAddress(params).toString();
//            Log.d(LOG_TAG, "wfa=" + wfa);
        } catch (IllegalArgumentException iae) {
            FirebaseCrash.report(iae);
            Log.e("psd", "restoreFromBlockByXPRV: " + iae.toString());
            callback.onWalletCreated(wallet);
        }
    }

    public void restoreFromBlockByWif(String wif, WalletCreationCallback callback) {
        wif = wif.trim();
        try {
            DumpedPrivateKey dumpedPrivateKey = DumpedPrivateKey.fromBase58(params, wif);
            ECKey key = dumpedPrivateKey.getKey();
            wallet = new Wallet(params);
            wallet.importKey(key);
            restFromWif = true;
            walletFriendlyAddress = wallet.getImportedKeys().get(0).toAddress(params).toString();
            wifKey = wif;
        } catch (IllegalArgumentException iae) {
            FirebaseCrash.report(iae);
            Log.e("psd", "restoreFromBlockByWif: " + iae.toString());
            callback.onWalletCreated(wallet);
            return;
        }

        callback.onWalletCreated(wallet);

        requestUnspents();
    }

    public void restoreFromBlock0(String mnemonicCode, Runnable callback) {
        if (mnemonicCode.charAt(mnemonicCode.length() - 1) == ' ') {
            mnemonicCode = mnemonicCode.substring(0, mnemonicCode.length() - 1);
        }

        DeterministicSeed seed = new DeterministicSeed(Splitter.on(' ').splitToList(mnemonicCode), null, "", 0);

        wallet = Wallet.fromSeed(params, seed);
        mnemonicKey = Joiner.on(" ").join(seed.getMnemonicCode());
        //sharedManager.setLastSyncedBlock(Coders.encodeBase64(mnemonicKey));

        walletFriendlyAddress = wallet.currentReceiveAddress().toString();

        callback.run();

        requestUnspents();
    }


    public void restoreFromBlock2(String mnemonicCode, Runnable callback) {
        if (mnemonicCode.charAt(mnemonicCode.length() - 1) == ' ') {
            mnemonicCode = mnemonicCode.substring(0, mnemonicCode.length() - 1);
        }

        DeterministicSeed seed = new DeterministicSeed(Splitter.on(' ').splitToList(mnemonicCode), null, "", 0);

        wallet = Wallet.fromSeed(params, seed);
        mnemonicKey = Joiner.on(" ").join(seed.getMnemonicCode());
//        sharedManager.setLastSyncedBlock(Coders.encodeBase64(mnemonicKey));

        walletFriendlyAddress = wallet.currentReceiveAddress().toString();

        ChildNumber childNumber = new ChildNumber(ChildNumber.HARDENED_BIT);
        DeterministicKey de = wallet.getKeyByPath(ImmutableList.of(childNumber));
        xprvKey = de.serializePrivB58(params);

        wifKey = wallet.currentReceiveKey().getPrivateKeyAsWiF(params);

        callback.run();

        requestUnspents();
    }

    public void restoreFromBlockByXPRV2(String xprv, Runnable callback) {
        xprv = xprv.trim();
        try {
            DeterministicKey dk01 = DeterministicKey.deserializeB58(xprv, params);
            DeterministicHierarchy dh = new DeterministicHierarchy(dk01);
            List<ChildNumber> chns = ImmutableList.of(new ChildNumber(ChildNumber.HARDENED_BIT), new ChildNumber(0, false));
            DeterministicKey dk03 = dh.deriveChild(chns, false, true, new ChildNumber(0, false));
            xprvKey = xprv;
            Log.d(LOG_TAG, "m/0'/0=" + dk03.getPrivateKeyAsWiF(params) + " privkey=" + dk03.getPrivateKeyAsHex());
            restoreFromBlockByWif2(dk03.getPrivateKeyAsWiF(params), callback);
        } catch (IllegalArgumentException iae) {
            FirebaseCrash.report(iae);
            Log.e("psd", "restoreFromBlockByXPRV2: " + iae.toString());
            callback.run();
        }
    }

    public void restoreFromBlockByWif2(String wif, Runnable callback) {
        wif = wif.trim();
        try {
            DumpedPrivateKey dumpedPrivateKey = DumpedPrivateKey.fromBase58(params, wif);
            ECKey key = dumpedPrivateKey.getKey();
            wallet = new Wallet(params);
            wallet.importKey(key);
            restFromWif = true;
            walletFriendlyAddress = wallet.getImportedKeys().get(0).toAddress(params).toString();
            wifKey = wif;
        } catch (WrongNetworkException wne) {
            Log.e("psd", "restoreFromBlockByWif2: " + wne.toString());
            callback.run();
            return;
        } catch (IllegalArgumentException iae) {
            FirebaseCrash.report(iae);
            Log.e("psd", "restoreFromBlockByWif2: " + iae.toString());
            callback.run();
            return;
        }

        callback.run();

        requestUnspents();
    }

    public void requestUnspents() {
        String address = restFromWif ? walletFriendlyAddress : wallet.currentReceiveAddress().toString();
        RequestorBtc.getUTXOListBchSv(address, new ApiMethods.RequestListener() {
            @Override
            public void onSuccess(Object response) {
                List<UTXOItem> utxos = (List<UTXOItem>)response;
                setUTXO(utxos);
            }

            @Override
            public void onFailure(String msg) {

            }
        });
    }

    private void setUTXO(List<UTXOItem> utxoList) {
        Address a;
        if (restFromWif) {
            a = wallet.getImportedKeys().get(0).toAddress(params);
        } else {
            a = wallet.currentReceiveAddress();
        }

        final List<UTXO> utxos = new ArrayList<>();

        for (UTXOItem utxo : utxoList) {
            Sha256Hash hash = Sha256Hash.wrap(utxo.getTxHash());
            utxos.add(new UTXO(hash, utxo.getTxOutputN(), Coin.valueOf(utxo.getSatoshiValue()),
                    0, false, ScriptBuilder.createOutputScript(a)));
        }

        UTXOProvider utxoProvider = new UTXOProvider() {
            @Override
            public List<UTXO> getOpenTransactionOutputs(List<Address> addresses) throws UTXOProviderException {
                return utxos;
            }

            @Override
            public int getChainHeadHeight() throws UTXOProviderException {
                return Integer.MAX_VALUE;
            }

            @Override
            public NetworkParameters getParams() {
                return wallet.getParams();
            }
        };
        wallet.setUTXOProvider(utxoProvider);
    }

    public static String getFriendlyBalance(Coin coin) {
        String[] arr = coin.toFriendlyString().split(" ");
        return arr[0];
    }

    public Coin getMyBalance() {
        return myBalance != null ? myBalance : Coin.ZERO;
    }

    public void setMyBalance(Long satoshi) {
        myBalance = satoshi != null ? Coin.valueOf(satoshi) : Coin.ZERO;
    }

    public void setBalance(long balance) {
        Coin coin = balance != 0 ? Coin.valueOf(balance) : Coin.ZERO;
        this.balance = new BigDecimal(coin.toPlainString());
    }

    public BigDecimal getBalance() {
        return this.balance;
    }

    public String getPrivateKey() {
        return mnemonicKey;
    }

    public String getXPRV() {
        return xprvKey;
    }

    public String getWifKey() {
        if (wallet != null) {
            return wifKey;
        } else {
            return "";
        }
    }

    public String getWalletFriendlyAddress() {
        if (walletFriendlyAddress != null && !walletFriendlyAddress.isEmpty()) {
            CashAddressFactory cashAddressFactory = CashAddressFactory.create();
            Address cashAddress = cashAddressFactory.getFromBase58(params, walletFriendlyAddress);
            return cashAddress.toString();
        } else {
            return null;
        }
    }

    public String getWalletAddressForDeposit() {
        if (walletFriendlyAddress != null && !walletFriendlyAddress.isEmpty()) {
            CashAddressFactory cashAddressFactory = CashAddressFactory.create();
            Address cashAddress = cashAddressFactory.getFromBase58(params, walletFriendlyAddress);
            return cashAddress.toString();
        } else {
            return null;
        }
    }

    public String getLegacyAddress() {
        return walletFriendlyAddress;
    }

    public void setWallet(Wallet wallet) {
        this.wallet = wallet;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public Credentials getCredentials() {
        return null;
    }

    public String getWalletAddressWithoutPrefix() {
        return walletFriendlyAddress;
    }

    public void clearWallet() {
        wallet = null;
        walletFriendlyAddress = null;
        mnemonicKey = "";
        myBalance = Coin.ZERO;
        xprvKey = "";
        wifKey = "";
        restFromWif = false;
    }

    public boolean isValidPrivateKey(String key) {
        String[] words = key.split("\\W+");

        if (words.length != MNEMONIC_WORDS_COUNT) {
            return false;
        }
        for (String word : words) {
            if (!mBip39Words.contains(word)) {
                return false;
            }
        }
        return true;
    }

    public boolean isSimilarToAddress(String text) {
        return isAddressValid(text);
    }

    public void isAddressValid(String address, final Callback<Boolean> callback) {
        try {
            if (address.indexOf("bitcoincash:") == 0) {
                CashAddressFactory cashAddressFactory = CashAddressFactory.create();
                cashAddressFactory.getFromFormattedAddress(params, address);
            } else {
                Address.fromBase58(params, address);
            }
            callback.onResponse(true);
        } catch (WrongNetworkException wne) {
            Log.e("psd", "isAddressValid: " + wne.toString());
            callback.onResponse(false);
        } catch (AddressFormatException afe) {
            Log.e("psd", "isAddressValid: " + afe.toString());
            callback.onResponse(false);
        } catch (Exception e) {
            e.printStackTrace();
            callback.onResponse(false);
        }
    }

    public boolean isAddressValid(String address) {
        try {
            return !address.isEmpty()
                    && params.equals(Address.getParametersFromAddress(address));
        } catch (Exception e) {
            return false;
        }
    }

    public static String SMALL_SENDING = "insufficientMoney";
    public static String NOT_ENOUGH_MONEY = "notEnough";

    public String generateHexTx(String toAddress, long sumIntoSatoshi, long feeIntoSatoshi) {
        Address RECEIVER;
        if (toAddress.indexOf("bitcoincash:") == 0) {
            CashAddressFactory cashAddressFactory = CashAddressFactory.create();
            RECEIVER = cashAddressFactory.getFromFormattedAddress(params, toAddress);
        } else {
            RECEIVER = Address.fromBase58(params, toAddress);
        }


        Coin AMOUNT = Coin.valueOf(sumIntoSatoshi);
        Coin FEE = Coin.valueOf(feeIntoSatoshi);

        Log.d("svcom", "tx - amount = " + AMOUNT.toFriendlyString() + " fee = " + FEE.toFriendlyString());
        /**
         * available default fee
         * Transaction.REFERENCE_DEFAULT_MIN_TX_FEE;
         * Transaction.DEFAULT_TX_FEE;
         */
        SendRequest sendRequest = SendRequest.to(RECEIVER, AMOUNT);
        if (restFromWif) {
            sendRequest.changeAddress = wallet.getImportedKeys().get(0).toAddress(params);
        } else {
            sendRequest.changeAddress = wallet.currentReceiveAddress();
        }
        sendRequest.ensureMinRequiredFee = true;
        sendRequest.setUseForkId(true);
        sendRequest.feePerKb = FEE;

        Transaction trx = null;
        String hex = "";
        try {
            //trx = wallet.sendCoinsOffline(sendRequest);
            wallet.completeTx(sendRequest);
            trx = sendRequest.tx;

            Log.d("svcom", "size = " + trx.bitcoinSerialize().length);
            hex = Hex.toHexString(trx.bitcoinSerialize());
            Log.d("svcom", "hex: " + hex);
        } catch (InsufficientMoneyException e) {
            e.printStackTrace();
            return NOT_ENOUGH_MONEY;
        } catch (Wallet.DustySendRequested e) {
            e.printStackTrace();
            return SMALL_SENDING;
        }
        return hex;
    }


    public long calculateFee(String toAddress, long sumIntoSatoshi, long feeIntoSatoshi) throws Exception {
        Address RECEIVER;
        if (toAddress.indexOf("bitcoincash:") == 0) {
            CashAddressFactory cashAddressFactory = CashAddressFactory.create();
            RECEIVER = cashAddressFactory.getFromFormattedAddress(params, toAddress);
        } else {
            RECEIVER = Address.fromBase58(params, toAddress);
        }
        Coin AMOUNT = Coin.valueOf(sumIntoSatoshi);
        Coin FEE = Coin.valueOf(feeIntoSatoshi);
        SendRequest sendRequest = SendRequest.to(RECEIVER, AMOUNT);
        if (restFromWif) {
            sendRequest.changeAddress = wallet.getImportedKeys().get(0).toAddress(params);
        } else {
            sendRequest.changeAddress = wallet.currentReceiveAddress();
        }
        sendRequest.ensureMinRequiredFee = true;
        sendRequest.setUseForkId(true);
        sendRequest.feePerKb = FEE;
        long result = FEE.getValue();
        try {
            Log.d("flint", "WalletManager.calculateFee().... wallet.getBalance(): " + wallet.getBalance());
            wallet.completeTx(sendRequest);
            result = sendRequest.tx.getFee().getValue();
        } catch (InsufficientMoneyException e) {
            throw new Exception("NOT_ENOUGH_MONEY");
        } catch (Wallet.DustySendRequested e) {
            throw new Exception("SMALL_SENDING");
        } catch (Exception e) {
            throw new Exception(e.toString());
        }
        return result;
    }



    public String cashAddressToLegacy(String cashAddress) {
        try {
            CashAddressFactory cashAddressFactory = CashAddressFactory.create();
            Address leg = cashAddressFactory.getFromFormattedAddress(params, cashAddress);
            Log.d("psd", "cash to addr: leg addr = " + leg.toBase58());
            return leg.toBase58();
        } catch (AddressFormatException afe) {
            afe.printStackTrace();
            return "";
        }
    }



    public static CharSequence isCharSequenceValidForAddress(CharSequence charSequence) {
        final String validChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ01234567890";
        if (charSequence.length() > 10) // for qr-scanner
            return null;
        String res = "";
        for (int i = 0; i < charSequence.length(); ++i) {
            char c = charSequence.charAt(i);
            if (validChars.indexOf(c) >= 0)
                res += c;
        }
        return res;
    }

}