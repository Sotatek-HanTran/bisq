/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.trade.protocol.tasks.seller_as_taker;

import bisq.core.btc.model.AddressEntry;
import bisq.core.btc.model.RawTransactionInput;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.trade.Contract;
import bisq.core.trade.Trade;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.trade.protocol.tasks.TradeTask;

import bisq.common.crypto.Hash;
import bisq.common.taskrunner.TaskRunner;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class SellerAsTakerSignsDepositTx extends TradeTask {
    @SuppressWarnings({"unused"})
    public SellerAsTakerSignsDepositTx(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            log.debug("\n\n------------------------------------------------------------\n"
                    + "Contract as json\n"
                    + trade.getContractAsJson()
                    + "\n------------------------------------------------------------\n");

            byte[] contractHash = Hash.getSha256Hash(checkNotNull(trade.getContractAsJson()));
            trade.setContractHash(contractHash);

            List<RawTransactionInput> sellerInputs = checkNotNull(processModel.getRawTransactionInputs(), "sellerInputs must not be null");
            BtcWalletService walletService = processModel.getBtcWalletService();
            String id = processModel.getOffer().getId();

            Optional<AddressEntry> addressEntryOptional = walletService.getAddressEntry(id, AddressEntry.Context.MULTI_SIG);
            checkArgument(addressEntryOptional.isPresent(), "addressEntryOptional must be present");
            AddressEntry sellerMultiSigAddressEntry = addressEntryOptional.get();
            byte[] sellerMultiSigPubKey = processModel.getMyMultiSigPubKey();
            checkArgument(Arrays.equals(sellerMultiSigPubKey,
                    sellerMultiSigAddressEntry.getPubKey()),
                    "sellerMultiSigPubKey from AddressEntry must match the one from the trade data. trade id =" + id);

            Coin sellerInput = Coin.valueOf(sellerInputs.stream().mapToLong(input -> input.value).sum());

            sellerMultiSigAddressEntry.setCoinLockedInMultiSig(sellerInput.subtract(trade.getTxFee().multiply(2)));
            walletService.saveAddressEntryList();

            TradingPeer tradingPeer = processModel.getTradingPeer();

            Transaction depositTx = processModel.getTradeWalletService().takerSignsDepositTx(
                    true,
                    contractHash,
                    processModel.getPreparedDepositTx(),
                    checkNotNull(tradingPeer.getRawTransactionInputs()),
                    sellerInputs,
                    tradingPeer.getMultiSigPubKey(),
                    sellerMultiSigPubKey);

            trade.applyDepositTx(depositTx);

            complete();
        } catch (Throwable t) {
            Contract contract = trade.getContract();
            if (contract != null)
                contract.printDiff(processModel.getTradingPeer().getContractAsJson());
            failed(t);
        }
    }
}
