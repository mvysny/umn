package compat

import org.junit.jupiter.api.Nested
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
class OnProdServerTest {
    @Nested inner class Wire : WireTestBattery(ProdServer.shared)
    @Nested inner class PhoneUpgrade : PhoneUpgradeTestBattery(ProdServer.shared)
    @Nested inner class MixedFleet : MixedFleetTestBattery(ProdServer.shared)
    @Nested inner class Admin : AdminTestBattery(ProdServer.shared)
    @Nested inner class Downgrade : DowngradeTestBattery(ProdServer.shared)
}

/**
 * Holds HEAD's proxy server to [ProdServer]'s wire and admin API, so the server can change underneath 0.15 nodes.
 */
class OnHeadServerTest {
    @Nested inner class Wire : WireTestBattery(HeadServer.shared)
    @Nested inner class PhoneUpgrade : PhoneUpgradeTestBattery(HeadServer.shared)
    @Nested inner class MixedFleet : MixedFleetTestBattery(HeadServer.shared)
    @Nested inner class Admin : AdminTestBattery(HeadServer.shared)
    @Nested inner class Downgrade : DowngradeTestBattery(HeadServer.shared)
}
