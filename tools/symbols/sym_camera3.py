STAGE_SWITCH = "com.oplus.camera.protocal.event.message.modeProtocol.EventMessageModeSwitcherStage"
add(
 # capture stage: the request builder field (C) is the one whose type takes the picture callback
 StageRequest__pictureCallback=M(params=["com.oplus.camera.aps.service.ApsService$CameraPictureCallbackImpl"], ret="void", abstract=True),
 StageRequestType=K(ownerOf="@StageRequest.pictureCallback"),
 Stage__request=F(**{"in": STAGE}, type="@StageRequestType"),
 # mode switch stage (non-obfuscated class; field names checked with their types)
 ModeSwitchStage__stage=F(**{"in": STAGE_SWITCH}, name="b", type="int"),
 ModeSwitchStage__toMode=F(**{"in": STAGE_SWITCH}, name="e", type=S),
)

MSB = "com.oplus.camera.control.MainShutterButton"
add(
 # PKJ110 (X8U) camera v7.006.123 has no I6:Bitmap field in ZoomSeekBar - optional so the
 # status page does not count a device difference as a failure.
 Zoom__labelBitmap=F(**{"in": ZSB}, name="I6", type="android.graphics.Bitmap", optional=True),
 Zoom__fadeLabels=M(**{"in": ZSB}, name="b", params=[], ret="void"),
 ZoomBase__fadeLabels=M(**{"in": "@ZoomBase"}, overrideOf="@Zoom.fadeLabels"),
 ZoomUi__bar=F(**{"in": "@ZoomUi"}, readBy=["@ZoomUi.setUnit"]),
 Card__texts=F(**{"in": "@Card"}, type="com.oplus.camera.common.view.StrokeTextView", multi=True),
 SoundPlayer__refreshedInts=F(**{"in": "@SoundPlayer"}, type="int", writtenBy=["@SoundPlayer.refresh"], multi=True),
)
for n in ["b3", "c3", "d3", "e3", "g3", "h3", "l3"]:
    add(**{"Shutter__" + n: F(**{"in": MSB}, name=n, type="android.graphics.RectF")})
add(Shutter__t4=F(**{"in": MSB}, name="t4", type="android.graphics.SweepGradient"),
    Shutter__H4=F(**{"in": MSB}, name="H4", type="android.graphics.LinearGradient"),
    Shutter__I4=F(**{"in": MSB}, name="I4", type="android.graphics.LinearGradient"),
    Shutter__s3=F(**{"in": MSB}, name="s3", type="android.graphics.Paint"),
    Shutter__j1=F(**{"in": MSB}, name="j1", type="int"),
    Shutter__k1=F(**{"in": MSB}, name="k1", type="int"))

# BitmapUtil.cropCenterBitmapByScale: returns null when the scale is below 1 (SeamlessBlurGuard)
add(BitmapUtil__cropCenterByScale=M(params=["android.graphics.Bitmap", "float", "float"], ret="android.graphics.Bitmap", static=True,
                                    strings=["cropCenterBitmapByScale, error, sw: "]))

# master effect bar (影调 / 饱和度 / … / 暗角): MasterGrain adds 颗粒 before 暗角, with the professional
# white balance / colour tone pair of scale bars for its strength and size
EM = "@EffectMenuBase"
SB = "com.oplus.camera.coui.seekbar.HSScaleBar"
add(
 EffectMenu=K(strings=["MasterEffectMenuV2"]),
 EffectMenuBase=K(superOf="@EffectMenu"),
 EffectMenuBase__items=F(**{"in": EM}, type="java.util.ArrayList"),
 EffectMenuBase__seekBar=F(**{"in": EM}, type=SB),
 EffectMenuBase__restore=M(**{"in": EM}, params=["int", "boolean"], strings=["BaseMasterEffectMenu"]),
 EffectMenuBase__report=M(**{"in": EM}, strings=["funcValue_select"]),
 EffectMenuBase__hideSeekBar=M(**{"in": EM}, params=["boolean"], ret="void", invokes=["@EffectMenuBase.report"]),
 EffectMenuBase__refreshItem=M(**{"in": EM}, params=["int"], ret="void", calledBy=["@EffectMenuBase.restore"]),
 EffectMenuBase__refreshReset=M(**{"in": EM}, params=["boolean"], ret="void", calledBy=["@EffectMenuBase.restore"]),
 # the menu's host (FilterSource): showing a scale bar hides the filter name card and dots, hiding it shows them
 EffectHost=K(interfaceOf="@FilterSource"),
 EffectMenuBase__host=F(**{"in": EM}, type="@EffectHost"),
 EffectMenu__showSeekBar=M(**{"in": "@EffectMenu"}, strings=["showSeekBar, menu is not opened, return"]),
 EffectMenu__hideSeekBar=M(**{"in": "@EffectMenu"}, overrideOf="@EffectMenuBase.hideSeekBar"),
 FilterSource__hideFilterUi=M(**{"in": "@FilterSource"}, params=[], ret="void", strings=["com.oplus.feature.effect.style.support"], calledBy=["@EffectMenu.showSeekBar"]),
 FilterSource__showFilterUi=M(**{"in": "@FilterSource"}, params=[], ret="void", strings=["com.oplus.feature.effect.style.support"], calledBy=["@EffectMenu.hideSeekBar"]),
 # the camera's vibrator (VibrateManager): play(1, 0) strong tick, play(0, 0) light tick, as the master bar's scale ticks
 Vibrate=K(strings=["blockVibration, invalid block option: "]),
 Vibrate__instance=F(type="@Vibrate", static=True),
 Vibrate__play=M(**{"in": "@Vibrate"}, params=["int", "int"], ret="void"),
 EffectMenu__refreshReset=M(**{"in": "@EffectMenu"}, overrideOf="@EffectMenuBase.refreshReset"),
 # "any item differs from its default", which enables the reset button
 EffectMenu__modified=M(**{"in": "@EffectMenu"}, params=[], ret="boolean", calledBy=["@EffectMenu.refreshReset"]),
 # CaptureParamPresenter's value callback: stores the value per preset, then applies the stock axes
 EffectMenu__dispatch=M(params=["int", DK], ret="void", calledBy=["@EffectMenuBase.restore"]),
 EffectItem__key=F(type=DK, static=False, readBy=["@EffectMenuBase.restore"]),
 EffectItem=K(ownerOf="@EffectItem.key"),
 EffectItem__value=F(**{"in": "@EffectItem"}, type="int", writtenBy=["@EffectMenuBase.restore"]),
 EffectItem__label=F(**{"in": "@EffectItem"}, name="a", type="int"),
 EffectItem__icon=F(**{"in": "@EffectItem"}, name="b", type="int"),
 # the item at an index has a -100..100 scale (冷暖, 青品, 饱和度, 影调); others 0..100
 EffectMenu__bipolar=M(**{"in": "@EffectMenu"}, params=["int"], ret="boolean"),
 EffectContainer=K(strings=["MasterEffectContainer"], super="android.widget.LinearLayout"),
 # collapsed, the row is shifted by half of (bar width - row width) to centre it; the bar width is the scroll view's
 # less twice sideMargin. updateBounds recomputes the reset capsule and the item backdrop from the row's position
 EffectContainer__sideMargin=F(**{"in": "@EffectContainer"}, name="q", type="int"),
 EffectContainer__updateBounds=M(**{"in": "@EffectContainer"}, params=[], ret="void", strings=["updateBlurBgBounds, calLeftAndRight is null, return"]),
 # Name-free shape (super + the four widget/effect member types); the MasterEffectContainer/
 # expand strings version does not match PKJ110 v7.006.123 (X9U only).
 EffectItemView=K(super="java.lang.Object",
    fieldTypes=["android.widget.ImageView", "android.widget.TextView", "@EffectItem", "@EffectContainer"],
    optional=True),
 EffectItemView__onClick=M(**{"in": "@EffectItemView"}, name="onClick", optional=True),
 EffectItemView__item=F(**{"in": "@EffectItemView"}, type="@EffectItem", optional=True),
 EffectItemView__bind=M(**{"in": "@EffectItemView"}, params=["boolean", "boolean"], ret="void", optional=True),
 EffectItemView__name=F(**{"in": "@EffectItemView"}, type="android.widget.TextView", optional=True),
 EffectItemView__icon=F(**{"in": "@EffectItemView"}, type="android.widget.ImageView", optional=True),
 EffectValueView=K(super="@EffectItemView", fieldTypes=["android.widget.TextView"], optional=True),
 EffectValueView__bind=M(**{"in": "@EffectValueView"}, overrideOf="@EffectItemView.bind", optional=True),
 EffectValueView__value=F(**{"in": "@EffectValueView"}, type="android.widget.TextView", optional=True),
 ScaleBar__setListener=M(**{"in": SB}, name="setScaleBarValueChangeListener"),
 ScaleBarListener=K(paramOf=["@ScaleBar.setListener", 0]),
 # position the bar on an index: select (index + notify flag), then scroll the scale to it
 ScaleBar__select=M(**{"in": SB}, name="L", params=["int", "boolean"], ret="void"),
 ScaleBar__scrollTo=M(**{"in": SB}, name="G", params=["int"], ret="void"),
 FilterNames__presenter=F(**{"in": "@FilterNames"}, type="@CaptureParams"),
 # current filter: the presenter's filter index into the mode's filter types (FilterNames.types)
 CaptureParams__filterIndex=M(**{"in": "@CaptureParams"}, params=[], ret="int", strings=["com.oplus.camera.feature.filter"]),
 # preference access: the store type names the DataKey group ("mmkv" / "sp")
 Prefs__storage=M(params=[], ret=S, static=True, strings=["mmkv", "sp"]),
 Prefs__get=M(params=[DK, S, "java.lang.Class", "java.lang.Object"], ret="java.lang.Object", static=True),
 Prefs__put=M(params=[DK, S, "java.lang.Class", "java.lang.Object"], ret="void", static=True,
              invokes=[f"L{DM.replace('.', '/')};->g(L{DK.replace('.', '/')};Ljava/lang/Object;)V"]),
)

# master LUT slots (MasterLutSlots): update() recombines the preview table from the current parameters
L3D = "com.oplus.camera.common.utils.Lut3dDataManager"
add(
 Lut3d__update=M(**{"in": L3D}, params=[], ret="void", strings=["updateBasicToneData, palette branch skip process, capModeName: "]),
)

# ===== r512: migrate the hardcoded 7.006.123 short names (XPAN entry, inspiration composition,
# pro-video LUT direct output) to fingerprint specs. The 317 re-obfuscation renamed every one of
# these; the same fingerprints resolve on both builds (verified by the sym.py replay).

add(
 # --- XPAN live-photo menu entry (XpanLiveFeature.hookMenuVisibility): stock removes the
 # live-photo option from the XPAN top-left menu; the module injects it back.
 # jq.h -> lq.f / dq.j -> eq.h / cq.l -> dq.j on 7.006.317.
 MenuOptionBase=K(strings=["MenuOptionInfo {optionKey: ", "setOptionValue fail! index: "]),
 MenuOptionTop=K(strings=["outFlashScale", "not VectorDrawable"]),
 CameraSettingUiTop=K(strings=["start_view", "expand_top_group"]),
 CameraUnit=K(strings=["ModeManager E ", ", isSwitchCamera: "]),
 # container method that receives the requested menu-key list: B(List,Z,COWAL) on 123, C on 317
 MenuContainer__injectList=M(**{"in": "@MenuContainer"},
                              params=["java.util.List", "boolean", "java.util.concurrent.CopyOnWriteArrayList"],
                              ret="void", strings=["CameraMenuContainer"]),
 # container method that inserts one option (calculateInsertIndex diagnostics): d on 123, a on 317
 MenuContainer__optionAdded=M(**{"in": "@MenuContainer"}, params=["@MenuOptionBase"], ret="void",
                              strings=["onMenuOptionAdded, menuOption not found in mMenuList: ",
                                       "calculateInsertIndex, menuListIndex: "]),
 # Camera activity -> CameraUnit getter: c0 on 123, d0 on 317 (w6 kept its name both builds)
 Camera__getCameraUnit=M(**{"in": "com.oplus.camera.w6"}, params=[], ret="@CameraUnit"),
 # LivePhotoPresenter support probe: P2()Z on 123, T2()Z on 317 (Optional ofNullable+orElse shape)
 LivePhotoPresenter__support=M(**{"in": "@LivePhotoPresenter"}, params=[], ret="boolean",
                               invokes=["Ljava/util/Optional;->ofNullable(Ljava/lang/Object;)Ljava/util/Optional;",
                                        "Ljava/util/Optional;->orElse(Ljava/lang/Object;)Ljava/lang/Object;"],
                               notInvokes=["Ljava/util/Optional;->map(Ljava/util/function/Function;)Ljava/util/Optional;"]),
 # onCheckForCapture: reads the intercept flag alongside onPreviewDrawn; G1 on 123, L1 on 317
 LivePhotoPresenter__captureCheck=M(**{"in": "@LivePhotoPresenter"},
                                    params=["com.oplus.camera.protocal.event.message.deviceProtocol.deviceControlRequest.EventMessageCaptureRequestStage"],
                                    ret="boolean",
                                    strings=["onCheckForCapture, wait for 1s cache."]),
 # r443 frame-budget fields: the intercept flag (V1 on 123, X1 on 317) is the one boolean read by
 # both onPreviewDrawn and onCheckForCapture.
 LivePhotoPresenter__interceptFlag=F(**{"in": "@LivePhotoPresenter"}, type="boolean",
                                     readBy=["@LivePhotoPresenter.onPreviewDrawn",
                                             "@LivePhotoPresenter.captureCheck"]),
 # the three pending-frame AtomicIntegers (h1/i1/j1 on 123, j1/k1/l1 on 317): every AtomicInteger
 # field of the presenter except the one enable() assigns
 LivePhotoPresenter__pendingCounters=F(**{"in": "@LivePhotoPresenter"},
                                       type="java.util.concurrent.atomic.AtomicInteger",
                                       notWrittenBy=["@LivePhotoPresenter.enable"], multi=True),
 # BaseMode session build / before-preview (the two names swapped between 123 and 317: Y7<->V7)
 BaseMode__buildSession=M(**{"in": "com.oplus.camera.module.BaseMode"}, params=[None], ret="void",
                          strings=["buildSession, set KEY_CAPTURE_WITH_SPRUCE"]),
 BaseMode__beforePreview=M(**{"in": "com.oplus.camera.module.BaseMode"}, params=[None], ret="void",
                           strings=["onBeforePreview, capMode: "]),
)

# --- inspiration composition (RealsceneProbe / CompositionFrameProbe): vb.c3 family on 123 ->
# wb.h3 family on 317.
add(
 CompPresenter=K(strings=["可用", "不可用", "p010", "time3", "time4"]),
 CompStateMachine=K(strings=["onImageReviewFailed, errCode: 0, errMsg: null"]),
 CompWorker=K(strings=["runComposition unexpected error: "]),
 CompManager=K(strings=["AICompositionThread", "persist.camera.composition.sat"]),
 CompStreamHelper=K(strings=["updateCompositionResult, skip crop, mZoom: ",
                             "applyDescToCropInfo, matched point not found, srcCrop: "]),
 CompSubject=K(strings=["desc1", "srcCrop", "crops"]),
 CompPoint=K(strings=["Point{x=", ", trackStatus="]),
 # ub.d -> vb.d: the parsed composition-results holder (crop pipeline)
 CompResults=K(strings=["CompositionResults{mOutputNum=", "compositionDesc"]),
 # ub.e -> vb.e: the per-frame tracked point; the item class RealsceneProbe hooks is its $a
 CompFramePoint=K(strings=["{frameId=", ", point="]),
 # vb.c3 member renames on 317: F2->L2, Z1->e2, n3->v3 (now 3 params), k4->A4
 CompPresenter__frameScheduler=M(**{"in": "@CompPresenter"},
                                 params=[None, None, None, "int", "int"], ret="void",
                                 strings=["CompositionProcessor"]),
 CompPresenter__effectTexture=M(**{"in": "@CompPresenter"},
                                params=["com.oplus.camera.protocal.event.message."
                                        "previewProtocol.EventMessagePreviewPostDraw"],
                                ret="void"),
 # the failure reporter logs every mode-failure reason; "no person detected" + "invalid zoom
 # result" together exclude the sibling reporter (d3/i3, which has failInspirationShootSameImport)
 CompPresenter__failure=M(**{"in": "@CompPresenter"}, ret="void",
                          strings=["no person detected", "invalid zoom result"]),
 CompPresenter__answerLanding=M(**{"in": "@CompPresenter"}, params=["int"], ret="void",
                                strings=["startSwitchButtonBusinessAfterLoginIfAlive, "
                                         "skip, presenter destroyed"]),
)

# --- pro-video LUT direct output (LogLutDirect): the 123 short names in the class constants.
add(
 ProVideoMode=K(strings=["master_video_", "pro_video_mode", "histogram"]),
 ProVideoModeAlt=K(strings=["aps_algo_pf", "aps_algo_yuvnight", "onCaptureCanceled"]),
 CameraImplementation=K(strings=["CameraUnitCallback", "ApsCaptureYuvThread", "closeCameraDevice X"]),
 # 123: bb.c1.v(Key,Object)V took the AE compensation write; 317 renames it to db.y0.b, and
 # db.y0.v is now the unrelated v(boolean). Fingerprint = the two-argument CaptureRequest$Key
 # setter (the sibling e() takes CameraParameter$PreviewKey, sink() takes a third boolean).
 CameraImplementation__ev=M(**{"in": "@CameraImplementation"},
                            params=["android.hardware.camera2.CaptureRequest$Key", "java.lang.Object"],
                            ret="void"),
 LutPersistence=K(strings=["lut_list.json", "LutFileCopyThread", "ProVideo_CloudyAmber"]),
 DataKeys=K(strings=["key_zoom_speed", "preview_switch", "pref_ai_hd_switch"]),
 # mm.o -> nm.p: dex-wide unique method a(ConfigureKey, Object)V (the stable adapter names it setParameter)
 SessionParams__put=M(name="a",
                      params=["com.oplus.ocs.camera.CameraParameter$ConfigureKey", "java.lang.Object"],
                      ret="void"),
 SessionParams=K(ownerOf="@SessionParams.put"),
 # om.e0 -> pm.b0; its $g keys inner class (super = the recorder config, both builds)
 ModeRecorder=K(strings=["stopRecord, end!", "onDestroy, block"]),
 VideoRecorder=K(strings=["EncodeYuv", " is wrong"]),
 AppCodec=K(strings=["ozoaudio", "max-bframes", "request-sync"]),
 SessionHolder=K(strings=["beforeSession", ", mTargetRect: "]),
 EncoderSurfaceCallback=K(strings=["OutputRenderer", "PreviewEffectProcessor"]),
 # mn.t -> mn.r: the per-frame Surface observer (surfaceCreated diagnostics)
 OutputProcessor__surfaceChanged=M(**{"in": "@OutputProcessor"}, params=["android.view.Surface"],
                                   ret="void", strings=["surfaceCreated"]),
 OutputProcessor=K(strings=["onSurfaceCreated mFinsh = false", "removePreviewSurface"]),
 EffectProcessor=K(strings=[", glReady: ", ", needDelayReCreateEngine: "]),
 PreviewRenderer=K(strings=["resetFirstFrameFlag", "notifyThumbnailBitmap", "queueEventForDrawFrame"]),
 OutputRenderer=K(strings=["onEglSurfaceDestroy", "onEglSurfaceCreated", "onDrawFrame, GL env is error"]),
 OutputRenderer__lutIndex=F(**{"in": "@OutputRenderer"}, type="int"),
 # nn.p.d0()V -> nn.q.f0()V: reads the LUT index field and mentions onEglSurfaceCreated
 OutputRenderer__surfaceReady=M(**{"in": "@OutputRenderer"}, params=[], ret="void",
                                fields=["@OutputRenderer.lutIndex"],
                                strings=["onEglSurfaceCreated"]),
 # rm.e method renames: Il()decision -> Jl, Hl()I -> Il, d7(I)I -> a7, Y7(session) -> V7.
 # The decision getter is pinned by its calls into the stable Vj/vk pair.
 ProVideoMode__valueIndex=M(**{"in": "@ProVideoMode"}, params=[], ret="int", numbers=[0, 2, 1, -1]),
 ProVideoMode__logFormat=M(**{"in": "@ProVideoMode"}, params=[], ret="boolean", strings=["off", "on"],
                           notInvokes=["Lcom/oplus/camera/configure/CameraConfig;->d(Ljava/lang/String;ZZ)Z"]),
 ProVideoMode__decision=M(**{"in": "@ProVideoMode"}, params=[],
                          invokes=["@ProVideoMode.valueIndex", "@ProVideoMode.logFormat"],
                          calledBy=["@ProVideoMode.session"]),
 ProVideoMode__logKind=M(**{"in": "@ProVideoMode"}, params=[], ret="int",
                         strings=["MasterVideoDecision"]),
 ProVideoMode__previewLut=M(**{"in": "@ProVideoMode"}, params=["int"], ret="int", numbers=[9, 10]),
 ProVideoMode__session=M(**{"in": "@ProVideoMode"}, params=[None], ret="void",
                         invokes=["@SessionParams.put"]),
 # jd.a -> kd.a: codec tuning keys
 CodecKeys=K(strings=["com.oplus.tunning.720p.hfr.fps", "com.oplus.tunning.1080p.hfr.second.fps"]),
 # o9.f -> q9.e: strategy that turns a decision into a HAL mode
 DecisionCallback=K(strings=["MasterToneLutByHal"]),
 # o9.h -> q9.g: the MasterVideoDecision feature table class (holds c(II)I)
 MasterVideoDecision=K(strings=["com.oplus.camera.master.video.feature.list",
                                "MasterVideoDecision"]),
 # n9.e -> p9.e: the master-video format preference keys (K0 = the log format key)
 LogFormatKeys=K(super="androidx.preference.e",
                 clinitStrings=["pref_scale_focus_key", "pref_is_auto_night_detected",
                                "pref_setting_key", "pref_camera_mode_key"]),
 # rm.e member renames discovered on 317: k -> m (createVideoRecorder)
 ModeRecorder__create=M(**{"in": "@ModeRecorder"}, ret="void",
                        strings=["createVideoRecorder, start!"]),
# om.e0.? -> pm.b0.M(IZ)Z: the stop request; it builds a throwaway recorder config and
# reads one selector key back as "isNeedPostStopTask" (r534 stop hang root cause)
ModeRecorder__stop=M(**{"in": "@ModeRecorder"}, params=["int", "boolean"],
                     ret="boolean", strings=["stopRecord, end!"]),
 # bb.c1.v(Key,Object,Z)V -> db.y0.y(Key,Object,Z)V: the final parameter sink
 CameraImplementation__sink=M(**{"in": "@CameraImplementation"},
                              params=["android.hardware.camera2.CaptureRequest$Key",
                                      "java.lang.Object", "boolean"],
                              ret="void", strings=["OneCameraImpl"]),
 # nn.l.A0(I)V -> nn.n.z0(I)V: the preview LUT setup entry (also reads the K callback)
 PreviewRenderer__lutSetup=M(**{"in": "@PreviewRenderer"}, params=["int"], ret="void",
                             strings=["com.oplus.camera.preview.hdr.support"]),
 PreviewRenderer__callback=F(**{"in": "@PreviewRenderer"}, name="K",
                             readBy=["@PreviewRenderer.lutSetup"]),
 # nn.p.f:Lnn/p$a -> nn.q.f:Lnn/q$a: the output LUT callback, read by surfaceReady
 OutputRenderer__callback=F(**{"in": "@OutputRenderer"}, name="f",
                            readBy=["@OutputRenderer.surfaceReady"]),
 # BaseMode l0(Z)V -> P(Z)V: the mode onDestroy boundary
 BaseMode__destroy=M(**{"in": "com.oplus.camera.module.BaseMode"}, params=["boolean"],
                     ret="void", strings=["onDestroy"]),
 # om.l.s()V -> pm.i.r()V: the App MediaCodec encoder startup
 AppCodec__startup=M(**{"in": "@AppCodec"}, ret="void",
                     strings=["initVideoCodec", "VideoCodecInputThread", "Encode YUV Thread"]),
 # LogLutDirect preview LUT type callback: the hook target is the concrete implementation of
 # the (stable, unobfuscated) IEffectProcessor$EffectProcessCallback interface. 123 ships
 # dm.u3b; 317 ships em.v3$b. The interface class name is stable, so declare its abstract
 # method by name and hook every implementation (implOf) wherever R8 puts them.
 PreviewLutType__decl=M(**{"in": "com.oplus.camera.filter.IEffectProcessor$EffectProcessCallback"},
                        name="getPreviewRenderLutType", params=[], ret="int"),
 PreviewLutTypeImpl=M(implOf="@PreviewLutType.decl"),
 # Lut3dData.paletteMode owns the palette whitelist (static boolean(String), "common|portrait|
 # night" gate): Lut3dDataManager.f on 123, ka.l0.h0 on 317 -- the curated spec deliberately
 # has no "in" so the fingerprint follows the method across classes; consumers must hook the
 # resolved Method instead of a hardcoded owner class.
)
