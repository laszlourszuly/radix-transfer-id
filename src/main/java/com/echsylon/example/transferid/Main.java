package com.echsylon.example.transferid;

public class Main {

    public static void main(String[] args) {
        Application application = new Application();
        application.start();

        String[] users = application.getUsers();
        if (users.length > 1) {
            application.sendTokens(users[0], users[1], 10, "Hello! I sent you 10 tokens");
            application.sendTokens(users[1], users[0], 4, "Thanks! I sent you 4 back");
        }
    }

}
