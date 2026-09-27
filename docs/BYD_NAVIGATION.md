# BYD navigation displays

Phone navigation arrows, next-turn distance and street names can appear on supported BYD displays. Ordinary operation requires no ADB, root, laptop or helper process. The map app must provide structured navigation metadata; compatibility is not guaranteed for every map app or version.

## Validated windshield path

Live guidance and street names were physically confirmed in both DiAuto and DiPlay on DiLink5.1 / Android13, firmware `BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys`. The standalone output is restricted to that firmware and the verified stock receiver version10601004/signing certificate. Other firmware is not implicitly enabled by this result. Existing cluster/SOME-IP outputs remain available on supported factory services; the DiPlay contributor independently reported DiLink5.0 cluster/HUD operation.

The app sends navigation-only broadcasts to the stock ClusterDebug receiver as its normal Android UID. Vendor output runs outside phone control callbacks. Street text uses the installed HAL's UTF-16LE chunk protocol, capped at48 UTF-16 units without splitting a surrogate pair. Output logs exclude street text.

Normal route end, disconnect, disabling navigation output and stale guidance trigger cleanup. Force-stop/process kill may leave the last instruction visible until the app opens again; a recovery journal handles that next launch. There is no guaranteed process-independent expiry. Run only one projection app at a time.

Enable BYD navigation in settings. In DiAuto it is opt-in under Navigation; in DiPlay it is enabled by default when available. Debug-only receivers/demos require Android's DUMP permission and are absent from release manifests. Development starter and vendor-access experiments are not part of the production navigation path.

## CarPlay map on the instrument cluster (experimental)

DiPlay can ask the iPhone for CarPlay's second, instrument-cluster screen and show it in the BYD cluster's map area. The iPhone renders this map itself; DiPlay decodes the stream onto the cluster projection display. No ADB, root or helper is needed.

Validated on DiLink5.0 / Android12, firmware `BYD-AUTO/DiLink5.0/DiLink5.0:12/SKQ1.230128.001/eng.build.20251111.182747:user/release-keys`, with Apple Maps on iOS 27:

- Turn on "CarPlay map on instrument cluster" under BYD navigation. The switch appears only when a cluster projection display is visible to the app.
- In the cluster's own steering-wheel menu, choose "Full screen navi" or "Small screen navi". "Turn on by navi" shows the map only during stock BYD navigation, so it keeps showing the arrow output instead.
- The cluster's speed readout stays visible. "Small screen navi" crops the same picture on the cluster side; Android does not report that crop.
- The car marker is placed through CarPlay's safe area: the centre of the panel (x 35–64 %, y 16–75 %), measured with a calibration grid to be clear of BYD's own readouts in both Full and Small screen navi. "Car marker · horizontal" (Left 40 % … Right 40 %) and "Car marker · vertical" (Up 30 % … Down 30 %) move it from there in 10 % steps of the panel; a small drawing of the cluster in settings shows where the marker will be. Near a panel edge the safe area shrinks so the marker stays at its centre.
- "Cluster map size" sets the stream size, which the cluster scales up to the panel: Standard (100 %, sharpest), Larger (83 %, 1600x600, default) or Largest (67 %). Apple Maps ignores the reported physical size on the cluster, so resolution is the only way to change the map's scale.

How it works: the iPhone lists the cluster content it offers in its `/info` request (`altScreenURLs`). DiPlay declares a second display of the cluster's size with no input devices and `initialURL=maps:/car/instrumentcluster/map`; without an initial URL the iPhone streams only a black frame. BYD exposes the cluster projection area as public presentation displays owned by `com.byd.containerservice`. The stock map's display (`fission_bg_XDJAScreenProjection`) is hidden from third-party apps, but its `shared_…_0` sibling is composited on top of it, so DiPlay shows a `Presentation` there.

Limits: the cluster window belongs to the CarPlay screen, so it stops while that screen is closed and the session runs in the background. Other map apps and other firmware are untested.
