package com.echsylon.example.transferid;

import com.radixdlt.client.application.RadixApplicationAPI;
import com.radixdlt.client.application.identity.RadixIdentities;
import com.radixdlt.client.application.translate.Action;
import com.radixdlt.client.application.translate.data.SendMessageAction;
import com.radixdlt.client.application.translate.tokens.MintTokensAction;
import com.radixdlt.client.application.translate.tokens.TransferTokensAction;
import com.radixdlt.client.application.translate.unique.PutUniqueIdAction;
import com.radixdlt.client.atommodel.accounts.RadixAddress;
import com.radixdlt.client.core.Bootstrap;
import com.radixdlt.client.core.atoms.particles.RRI;
import io.reactivex.disposables.CompositeDisposable;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.charset.StandardCharsets.UTF_8;

class Application {
    private final AtomicBoolean isStarted;
    private final CompositeDisposable disposables;
    private final Map<String, RadixApplicationAPI> users;

    private RadixApplicationAPI system;
    private RRI token;

    Application() {
        disposables = new CompositeDisposable();
        isStarted = new AtomicBoolean(false);
        users = new HashMap<>();
    }

    void start() {
        if (!isStarted.getAndSet(true)) {
            setupSystem();
            setupUsers();
            setupTokens();

            users.forEach((name, api) -> {
                observeTransfers(name, api);
                observeMessages(name, api);
                observeBalance(name, api);
            });
        }
    }

    void stop() {
        if (isStarted.getAndSet(false)) {
            disposables.clear();
            users.clear();
        }
    }

    String[] getUsers() {
        int count = users.size();
        return users.keySet().toArray(new String[count]);
    }

    void sendTokens(String from, String to, double amount, String message) {
        // Get the API objects matching the provided user names.
        RadixApplicationAPI sender = Objects.requireNonNull(users.get(from), "No such user: " + from);
        RadixApplicationAPI receiver = Objects.requireNonNull(users.get(to), "No such user: " + to);

        // We will now create our custom atom (Transaction) and add our
        // relevant particles (Actions) to it. When finished, the atom
        // will contain a unique id particle, a token transfer particle
        // and an optional message particle.
        //
        // The atom will be rejected by the ledger if any particle fails
        // to validate properly. The unique id particle is validate such
        // that only one instance of it is allowed in the given address
        // (the unique id is an RRI of the form "/address/unique").
        //
        // The trick here is to attach the "unique" as an attachment to
        // the token transfer particle as well. This is our application
        // side transfer id and it can be retrieved later in, say, the
        // token transfer observer.
        //
        // If we want the transfer id, but not the singularity feature,
        // we can simply omit the unique id particle and just keep the
        // transfer attachment.
        RRI transferId = getUniqueId(sender);
        Action uniqueIdAction = PutUniqueIdAction.create(transferId);
        Action transferAction = TransferTokensAction.create(token,
                sender.getAddress(),
                receiver.getAddress(),
                BigDecimal.valueOf(amount),
                transferId.toString().getBytes(UTF_8));

        RadixApplicationAPI.Transaction transaction = sender.createTransaction();
        transaction.stage(uniqueIdAction);
        transaction.stage(transferAction);

        if (message != null) {
            // The message particle doesn't have an attachment field, such
            // as the token transfer particle, hence we need to define a
            // custom data protocol that can handle the transfer id (if we
            // want the identification feature of messages too).
            String messageData = String.format("%s::%s",
                    transferId.toString(),
                    message);

            // The message will be encrypted such that only the sender and
            // the receiver can decrypt it.
            transaction.stage(SendMessageAction.create(
                    sender.getAddress(),
                    receiver.getAddress(),
                    messageData.getBytes(UTF_8),
                    /* encrypt */ true));
        }

        // Compose the atom and publish it to the ledger. Would we have
        // generated our unique id particle in such a way that duplicates
        // would be possible, then any collisions would be detected now
        // and *the entire atom* would be rejected (neither the tokens
        // would be sent, nor the message).
        transaction.commitAndPush().blockUntilComplete();
    }


    private void setupSystem() {
        system = RadixApplicationAPI.create(Bootstrap.BETANET, RadixIdentities.createNew());
        token = RRI.of(system.getAddress(), "TOK");
        system.createMultiIssuanceToken(token, "TOK", "Transfer ID Test Tokens").blockUntilComplete();
    }

    private void setupUsers() {
        disposables.clear();
        users.clear();
        users.put("alice", RadixApplicationAPI.create(Bootstrap.BETANET, RadixIdentities.createNew()));
        users.put("bob", RadixApplicationAPI.create(Bootstrap.BETANET, RadixIdentities.createNew()));
        users.forEach((name, api) -> disposables.add(api.pull()));
    }

    private void setupTokens() {
        // Let the system mint and send 1.000.000 tokens to all users.
        RadixAddress sender = system.getAddress();
        BigDecimal amount = BigDecimal.valueOf(1_000_000);
        users.forEach((name, api) -> {
            Action mintAction = MintTokensAction.create(token, sender, amount);
            Action sendAction = TransferTokensAction.create(token, sender, api.getAddress(), amount);

            RadixApplicationAPI.Transaction transaction = system.createTransaction();
            transaction.stage(mintAction);
            transaction.stage(sendAction);
            transaction.commitAndPush().blockUntilComplete();
        });
    }

    private void observeTransfers(String name, RadixApplicationAPI user) {
        disposables.add(user.observeTokenTransfers()
                .subscribe(transfer -> {
                    // The attachment is at this point a Base64 encoded byte
                    // array. It's a bit unclear to me if this is a feature
                    // or a bug. Either way, we need to decode it in order
                    // to make it "human friendly" again.
                    byte[] attachment = transfer.getAttachment().orElse(new byte[0]);
                    String transferId = new String(Base64.getDecoder().decode(attachment), UTF_8);
                    String data = transfer.getAmount().toString();
                    print(name.toUpperCase(), "Transfer", data, transferId);
                }));
    }

    private void observeMessages(String name, RadixApplicationAPI user) {
        disposables.add(user.observeMessages()
                .subscribe(message -> {
                    // When we attached our message to the atom, we also
                    // included the application generated transfer id, hence
                    // creating the custom message data protocol. Reading
                    // this message, we need to "decode" the protocol to
                    // distinguish message from id.
                    byte[] data = message.getData();
                    String text = new String(data, UTF_8);
                    String[] particles = text.split("::", 2);
                    String messageText = particles.length > 1 ? particles[1] : particles[0];
                    String transferId = particles.length > 1 ? particles[0] : "";
                    print(name.toUpperCase(), "Message", messageText, transferId);
                }));
    }

    private void observeBalance(String name, RadixApplicationAPI user) {
        disposables.add(user
                .observeBalance(token)
                .subscribe(balance -> print(
                        name.toUpperCase(),
                        "Balance",
                        balance.toString(),
                        null)));
    }

    private RRI getUniqueId(RadixApplicationAPI user) {
        return RRI.of(
                user.getAddress(),
                UUID.randomUUID()
                        .toString()
                        .replaceAll("[^A-Za-z0-9]", ""));
    }

    private void print(String name, String action, String data, String id) {
        int nameLength = Math.min(name.length(), 8);
        String nameTag = String.format("[%s]", name.substring(0, nameLength));

        int actionLength = Math.min(action.length(), 10);
        String actionTag = action.substring(0, actionLength);

        int dataLength = Math.min(data.length(), 40);
        String dataTag = data.substring(0, dataLength);

        String idTag = "";
        if (id != null) {
            int idLength = Math.min(id.length(), 40);
            idTag = id.substring(0, idLength);
        }

        System.out.println(String.format("%-10s%-10s%-40s%-40s",
                nameTag,
                actionTag,
                dataTag,
                idTag));
    }
}
