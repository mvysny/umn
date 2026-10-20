package compat

import org.junit.jupiter.api.Nested
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
class OnProdServerTest {
    @Nested inner class Wire : WireTestBattery(ProdServer)
    @Nested inner class PhoneUpgrade : PhoneUpgradeTestBattery(ProdServer)
    @Nested inner class MixedFleet : MixedFleetTestBattery(ProdServer)
    @Nested inner class Admin : AdminTestBattery(ProdServer)
}

/**
 * Holds HEAD's proxy server to [ProdServer]'s wire and admin API, so the server can change underneath 0.15 nodes.
 */
class OnHeadServerTest {
    @Nested inner class Wire : WireTestBattery(HeadServer)
    @Nested inner class PhoneUpgrade : PhoneUpgradeTestBattery(HeadServer)
    @Nested inner class MixedFleet : MixedFleetTestBattery(HeadServer)
    @Nested inner class Admin : AdminTestBattery(HeadServer)
}
