package compat;

/**
 * Where a node syncs: the proxy server's websocket and admin URLs, and the account.
 */
final class Remote {
    final String wsUrl;
    final String adminUrl;
    final String user;
    final String pass;

    Remote(String wsUrl, String adminUrl, String user, String pass) {
        this.wsUrl = wsUrl;
        this.adminUrl = adminUrl;
        this.user = user;
        this.pass = pass;
    }
}
