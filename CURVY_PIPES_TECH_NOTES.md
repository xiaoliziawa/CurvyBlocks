# Curvy Pipes 技术拆解

> 目的：给自己用 Java（NeoForge 1.21.1）实现一个新的"弯曲管道/弯曲方块"模组做参考。
> 来源：`libs/curvy_pipes-1.21.1-1.15.8.jar` 的 Java 反编译（`reverse-engineering/curvy_pipes-1.15.8/java`）、
> 原生 Rust 二进制的字符串池 / JNI 注册表 / Ghidra 伪代码（同目录 `native/`、`NATIVE_API.md`）、`default.yaml`、`en_us.json`。
> 标注「确认」的内容在 Java 源码或二进制字符串里能直接看到；标注「推断」的是从依赖库、函数名和数据流推出来的。

---

## 1. 总体架构

| 层 | 内容 | 依据 |
| --- | --- | --- |
| Java 薄壳（~25 个类） | 事件注册、渲染状态、GUI 外壳、能力（Capability）适配、跨模组桥接 | 确认 |
| Rust 核心（~63 个模块，3 MB 平坦机器码） | 图数据模型、曲线求解、碰撞、网格生成、协议、物流、GUI 布局、配置 | 确认 |
| 运行时字节码补丁 | 用 ASM + JVMTI 在运行时改写原版/其他模组类并生成 8 个匿名类 `cyb0124/curvy_pipes/0..7` 作为钩子，**没有用 Mixin** | 确认 |

对自己实现的意义：Rust 只是作者的实现选择，所有功能在 Java 里都有等价物。下面各节末尾给出 Java 对应方案。

Rust 侧用到的第三方库（确认）及其用途（推断）：

| 库 | 用途 |
| --- | --- |
| `faer`（稀疏 Cholesky/LDLT、AMD 排序） | 曲线形状求解：把整张网络的曲线控制点当作未知量，解稀疏线性方程组 |
| `nalgebra` + `parry3d-f64`（GJK/EPA、AABB、点-线段/四面体距离） | 管段与方块碰撞箱、管段与管段的相交测试，射线拾取 |
| `slotmap` | 节点/边的稳定 ID（索引 + 版本号） |
| `taffy`（flexbox/grid 布局） | 节点配置 GUI 的控件布局 |
| `regex` | 过滤器里的正则匹配 |
| `prost`（protobuf） | 网络包与存档字节流编码 |
| `serde` + `yaml-peg`、`serde_json` | YAML 配置解析、运行时生成配方 JSON |

---

## 2. 数据模型：不基于方块的管道网络

**确认：**

- 世界里**没有任何 Block / BlockEntity / Entity** 代表管道。管道网络是每个维度一份的 `SavedData`（`level.getDataStorage().computeIfAbsent(..., "curvy_pipes")`），内容是一个 `byte[]`（protobuf 编码）。
- 加载流程：`LevelEvent.Load` → 读出 `byte[]` → 交给核心 `loadLevel(level, dimId, bytes, chunkMap, savedData)`；保存时核心返回 `byte[]` 写回 `CompoundTag`。
- 网络按**区块索引**：`ChunkEvent.Load/Unload` 通知核心哪些区块处于活动状态；`ChunkWatchEvent.Watch/UnWatch` 通知核心哪个玩家开始/停止观察哪个区块，核心只向该玩家同步该区块内的边和节点（"按玩家的区块订阅做增量同步"）。初始化时用 `ChunkMap` 的 holder 表扫描已完全加载的区块（`scanChunks`）。
- 数据元素（从 protobuf 消息名恢复）：`Vec3i` 节点位置、`Vec3d` 浮点位置、节点（`nodes`）、边（`edges`，两端 `n0/n1`，`internals` 内部控制点，`steps` 采样段数）、`pipe_palette`（本维度用到的管道类型表，边只存索引）、`force_joint`、`EdgeState` / `ClientEdgeState`（每条边的运行时状态与客户端展示状态）、`NodeState`。
- 管道类型来自配置：`id / name / texture / diameter / variant{Item|Fluid|Energy: rate}`，直径 0.1 ~ 1.6 且无上限，多根管道可共处同一方块空间，也可与其他模组方块共存（除非方块带 `curvy_pipes:no_overlap` 标签）。

**推断：** 节点分"普通节点"和"关节（joint）"，多条边经过同一节点时默认平滑连续；"Force Joint" 把节点变成折角。`tree.rs` 里有高度平衡的树（断言 `edge.height == node.height - 1`），可能是网络的层级/连通结构索引；`journal.rs` 是变更日志，用于增量同步。

**Java 对应：**
`SavedData` 子类 + 自定义二进制编码（`FriendlyByteBuf` 或 protobuf-java）；用 `Long2ObjectMap<ChunkPos → 边列表>` 做空间索引；订阅 `ChunkWatchEvent` 做按玩家同步。节点/边用带版本号的 int ID（自己写一个简易 slotmap：`int[] freeList + int[] version`）。

---

## 3. 曲线数学

**确认：**

- 边有 `internals`（内部控制点）和 `steps`（采样段数）；`steps` 会随边一起发给客户端，客户端按 `steps` 把曲线离散成折线再生成管状网格。
- 客户端有"网格吸附"模式：`Off / Axial / Corner+ / Corner- / Edge+ / Edge-`（`curvy_pipes.grid`），并有一个专门的 GLSL 着色器在屏幕上光线步进式地画出当前吸附网格的辅助线（`CustomRender` 里第 3 个着色器，uniform `q[3].y` 就是网格模式）。
- 放置时的服务器校验错误码：`invalid_shape`、`too_far`、`angle_too_small`（关节处两分支夹角太小）、`node_in_joint`（节点不能落在关节内部）、`intersect_block`、`intersect_pipe`、`no_overlap`、`too_costly`。

**推断（依据 faer 稀疏 Cholesky + `physics.rs` / `graph/physics.rs` / `vars.rs` / `statics.rs`）：**

- 曲线形状不是逐条独立的样条，而是**整张网络一起求解**：把每条边的内部控制点当作未知量，把"曲线尽可能平直/弯曲能量最小 + 经过节点 + 关节处切线连续（或强制折角）"写成二次型，其法方程是稀疏对称正定矩阵，用 AMD 重排 + Cholesky 分解求解。`statics.rs` 缓存与拓扑相关、不随位置变化的部分；`vars.rs` 是未知量的编号表。这与"Force Joint"能改变整条管道走向的行为一致。
- `steps` 由弧长和直径决定，用于控制网格密度。

**Java 对应：**
- 最简：每条边用 Catmull-Rom / Hermite 样条，节点切线取相邻边方向的平均（平滑）或不共享（折角）。再做一次弧长重参数化，按固定步长采样。
- 想要相同手感（一动全局都跟着变）：把所有边的内部控制点串成向量 x，最小化 Σ‖x[i-1] - 2x[i] + x[i+1]‖²（离散弯曲能量），加上节点位置与切线约束，得到线性方程组；矩阵是带状/稀疏的，用 EJML 或自己写的共轭梯度就够了，规模在一个网络几百个点级别。

---

## 4. 碰撞、拾取与交互钩子

**确认：**

- 方块碰撞箱通过 `VoxelShape.forAllBoxes` 展平成 `double[]`（`getCollisionBoxes / getOutlineBoxes / getExtOutlineBoxes`，后者取 3×3×3 邻域），交给核心做管段 vs AABB 测试（parry3d GJK/EPA，推断管段建模为胶囊体或扫掠圆）。
- 用运行时字节码补丁把下列钩子插进原版（匿名类 `cyb0124/curvy_pipes/0` 的静态 native 方法，签名见 `NATIVE_API.md`）：
  - `(HitResult, Level, Vec3 from, Vec3 to) → HitResult`：射线检测后处理，使准星能瞄准管道；配合匿名类 `1`（自定义 `HitResult` 子类，`getType()`）。
  - `(double×6 AABB, Level) → boolean` 与 `(double×6, Vec3 motion, Level) → Vec3`：实体碰撞查询/位移修正，让玩家和实体撞在管道上。
  - `(HitResult) → ItemStack`：创造模式中键拾取管道物品。
  - `(boolean, boolean, HitResult, MultiPlayerGameMode)`、`(boolean) → boolean`：左键攻击/持续挖掘流程接管，实现破坏进度与 crumble 效果。
  - `(Level, int, int, int, int) → int` 等：红石信号查询与邻居更新（见第 8 节）。
  - `(Map)`：向 `RecipeManager` / 资源管理器注入运行时生成的数据（见第 9 节）。
- 输入拦截：`InputEvent.InteractionKeyMappingTriggered` 里先让核心处理攻击/使用，返回 true 则 `setCanceled`；服务器端用 `PlayerInteractEvent.RightClickBlock`（`setUseItem(TriState.FALSE)`）和 `RightClickItem`（取消并返回 `CONSUME` / `FAIL`）。
- "按住 [键] 避免瞄准方块/管道/节点"的修饰键是**疾跑键**（`mc.options.keySprint.isDown()` 每帧传给核心）。

**Java 对应：**
- 拾取：Mixin `GameRenderer#pick`（或 `Entity#pick`）在原版结果后追加自己的射线-管段测试，返回自定义 `HitResult` 子类；或者不改原版，只在 `InteractionKeyMappingTriggered` / `RenderHighlightEvent` 里自己做射线检测（简单但准星不会显示原版轮廓）。
- 实体碰撞：Mixin `Entity#collide` 或 `Level#getEntityCollisions`，把管段的包围盒切成若干小 AABB 追加进碰撞列表。
- 射线-管段：把曲线折线化后做射线与胶囊体（线段 + 半径）求交，`Vec3`/JOML 即可。

---

## 5. 渲染

**确认：**

- 事件：`RenderLevelStageEvent`
  - `AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS`：主管道网格。每帧把投影/模型视图矩阵、相机位置、屏幕尺寸、GUI 缩放、partialTick、主副手物品 ID 传给核心。
  - `AFTER_ENTITIES`：管道内移动的物品（`ItemRenderer.renderStatic(ItemDisplayContext.FIXED)`，姿态矩阵从核心写入的 `FloatBuffer` 取）。
  - `AFTER_TRIPWIRE_BLOCKS`：透明管道（`Sheets.translucentCullBlockSheet()`）。
- 网格由核心生成为 `MeshData`（`DrawState`：`Mode.TRIANGLES`、`IndexType.INT`，顶点格式在 `NEW_ENTITY / BLOCK / POSITION_COLOR / POSITION_TEX` 中选择），上传到 `VertexBuffer(Usage.STATIC)` 缓存，之后每帧 `vb.drawWithShader(view, proj, RenderSystem.getShader())`；绘制前把雾起点设为 `Float.MAX_VALUE`（管道不受雾影响）。
- RenderType：cutout 用 `NeoForgeRenderTypes.ITEM_LAYERED_CUTOUT_MIPPED`；透明用原版 translucent block sheet；GUI 用自定义 `POSITION_COLOR` 无深度类型。
- 纹理：直接从方块图集取 sprite（`TextureAtlasStitchedEvent`，`LOCATION_BLOCKS`），管道贴图放在 `block/` 目录；UV 沿曲线展开（推断：按弧长平铺）。
- 光照：核心按采样点向 Java 查询 `LightTexture.pack(blockLight, skyLight)`；有一个批量比对接口 `queryLight(int[] xyzLight...)`，逐点比较是否变化，只有变化才重建网格。
- 物品模型：不写 JSON，运行时用内存资源包（`ClientResources implements PackResources`，注入到 `FallbackResourceManager`）为每个管道物品提供 `{"parent":"item/template_shulker_box"}`，然后用 `BlockEntityWithoutLevelRenderer` + `IClientItemExtensions` 自定义渲染物品图标（同样调用核心生成网格）。
- 破坏进度：用 `ModelBakery.DESTROY_TYPES` 的 crumble 纹理再画一遍网格；破坏粒子用 `TerrainParticle` 并重写 `getU0/V0/U1/V1` 取管道 sprite 的随机子区域，同时播放 `STONE_BREAK`；流体烧毁效果用 `LAVA` 粒子 + `LAVA_EXTINGUISH`。
- HUD（`RenderGuiEvent.Pre`）：提示文字用 `Font.drawInBatch`；"更多选项"径向菜单用自定义 GLSL 程序（`CustomRender`，`POSITION_TEX`，预乘 alpha 混合，无深度）在一个全屏/局部四边形上用片元着色器直接画扇区、悬停高亮和噪声辉光；网格吸附辅助线是第三个着色器。
- Iris 兼容：绘制前临时关闭 `ImmediateState.renderWithExtendedVertexFormat`；检测 `ShadowRenderer.ACTIVE` 决定阴影通道行为。

**推断：** 管状网格是沿折线的圆环拉伸（每个采样点生成一圈顶点，相邻圈连成三角带），法线/切线沿 Frenet 或平行传输框架计算；网格缓存粒度是"每条边"或"每区块"。

**Java 对应：**
`RenderLevelStageEvent` + 自己维护 `Map<EdgeId, VertexBuffer>`，用 `BufferBuilder` 生成 `MeshData` 后 `VertexBuffer.upload`；光照用 `LevelRenderer.getLightColor` 或 `LightTexture.pack`；透明管道单独在 `AFTER_TRIPWIRE_BLOCKS` 画；径向菜单可以先用 `GuiGraphics.fill` 画扇形近似，不需要自定义 shader。

---

## 6. 编辑交互（客户端流程）

从 `en_us.json` 恢复的完整操作集（确认）：

- 开始：`start_free`（空中）、`start_block`（方块表面）、`start_extend`（延长已有管道端点）、`start_branch`（从节点分支）、`start_config`（打开节点配置）。
- 过程：`add_node`（添加中间节点）、`insert_node`（在已有边上插入节点）、`grid`（切换吸附网格）、`avoid_block / avoid_pipe / avoid_node`（按住疾跑键排除某类目标）。
- 结束：`finish_free / finish_block / finish_extend / finish_branch`。
- 径向菜单（长按 `more_options`）：`delete / branch / extend / move / force_joint / unforce_joint / config`。
- 移动：`confirm_move`，从方块上拿开会提示 `detach`（配置丢失）。
- 成本：放置需要消耗对应管道物品，数量随长度/直径变化（`cost`、`cost_alt`、`missing`），创造模式免费（`checkInstabuild`）；从玩家背包按"选中槽 → 主背包 → 副手"顺序收集（`gatherStacks / consumeStacks`），删除时返还（`giveStack`，放不下则掉落）。
- 客户端先预览并本地校验（`cant_place`），提交后显示 `wait`（"Waiting for server"），服务器返回 `PlaceResponse{result, missing_pipe, missing_qty, block_state}`，失败时显示 `fail_place / fail_insert / fail_delete / fail_move / fail_force_joint` + 原因。
- 用配置里的 `transparency_toggle_item`（默认玻璃）右键切换物品管道透明。
- 跨模组管道用**副手**拿原模组物品放置曲线变体（`offhand`），可配置 `OffHand / AnyHand / Disable`。

C2S 操作消息（从协议字符串恢复，确认名称）：`Place`、`Insert`（含 `pct` 边上的比例位置）、`Translate`（`Vec3d` 位移）、`ForceJoint` / `SetForceJoint`、`Break`、`RemoveEdge` / `Remove`、`Interact`、`Exit`、`SetNode` / `SetEdge` / `SetEdgeState`、`TrnItem` / `TrnToggle`、`Menu`。

---

## 7. 网络协议

**确认：**

- 只注册一个 `CustomPacketPayload`（`curvy_pipes:0`），`playBidirectional`，负载就是一个 `byte[]`；核心解码后返回 `String` 错误（null 表示成功），Java 只负责 `LOGGER.warn`。
- 字节流是 protobuf（prost 生成），顶层是 `S2c` / `C2s` 两个 oneof 包装消息。
- 菜单打开走原版 `player.openMenu(MenuProvider, buf -> buf.writeByteArray(data))`，客户端 `IMenuTypeExtension.create((id, inv, data) -> ...)` 用同一份 `byte[]` 重建菜单；菜单内的后续交互也走上面的 payload（`Menu` 消息 + `menu_id`）。
- 客户端每 tick 与服务器每 tick 各有一个 `tick()` 入口（`ClientTickEvent.Pre` / `ServerTickEvent.Post`）。

**Java 对应：** 一个 `CustomPacketPayload` + `StreamCodec`，内部再按消息类型分派；或者每种操作一个 payload，NeoForge 都支持。存档和网络可以共用同一套编码。

---

## 8. 物流系统

**确认：**

- 三种内置变体：物品（items/tick）、流体（mB/tick）、能量（FE/tick），速率可小于 1（如 0.1 items/tick），说明有累加式限速器（`rate_limit.rs`）。
- 节点端口模式：`Passive`、`Extract`（推到相连的被动端）、`Retrieve`（从相连的被动端拉取）；`Interval` 1–1200 tick（`tick_interval`），低=频繁小量，高=稀疏大量。
- 过滤器：放入物品/流体做白名单，`Deny` 反转；`Add Regex` 对命名空间 ID 做正则匹配（只接受"安全正则"，即有复杂度限制）；`Reg.` 保留量：对单个物品/流体或总量设上限或下限（"低于此量停止提取 / 高于此量停止插入"）。
- 提示 `no_buffer`："传输是即时的，管道没有内部缓冲"——物品是从源库存直接进目标库存，管道里看到的移动物品是客户端动画（`TrnItem`、`ClientEdgeState`，推断）。
- 库存访问全走能力系统：`Capabilities.ItemHandler.BLOCK / FluidHandler.BLOCK / EnergyStorage.BLOCK`，按端口朝向 `Direction.from3DDataValue(dir)`；只在区块已加载（`getChunkNow != null`）时访问。
- 物品库存快照（`ItemInv.read`）：遍历所有槽，把 `(itemId, componentsPatch.hashCode())` 相同的堆合并成"物品种类表 + 槽位→种类/数量映射"，之后核心只按种类做决策，再用 `simExtract / execExtract / insert(sim)` 执行；流体同理（`fluidAndPatchPreHash`）。Storage Drawers 有专门适配（直接操作 `IDrawer`）。
- 红石电缆（`redstone_cable`）：传导弱信号，内部不衰减；与原版红石线相连时衰减 1。通过钩子接入 `Level.getSignal`，并用 `neighborChanged / updateNeighborsAt` 触发更新。
- 配置可自动生成 4:1 上下转换合成表（`conversion_chains`，因子 2–9）。

**推断：** 同一变体的相连边构成一个 `Net`（连通分量），每个 Net 维护 `exits`（有配置的端口）和 `power`；每个 interval 对 Extract/Retrieve 端口执行一次匹配；能量按 Net 内的出口分配。

**Java 对应：** 端口对象持 `BlockCapabilityCache`；每 N tick 对每个 Net 做一次"源→目标"匹配；过滤器用 `java.util.regex` 并限制长度/禁止嵌套量词；保留量逻辑就是 count 比较。

---

## 9. 配置、注册与资源

**确认：**

- 配置是 YAML（`config/curvy_pipes.yaml`，缺省时用内置 `default.yaml`，并总是写出一份 `curvy_pipes_default.yaml`）。管道类型完全由配置定义，因此物品是在 `RegisterEvent`（`Registries.ITEM`）中按配置**动态注册**的；创造标签也在同一事件按核心返回的物品数组构建；菜单类型 `curvy_pipes:0`。
- 配方在运行时生成 JSON 字符串，通过钩子注入 `RecipeManager` 的 `Map<ResourceLocation, JsonElement>`（`applyRecipes`），不需要 data 文件。
- 物品模型在运行时由内存资源包提供（见第 5 节）。
- `FMLCommonSetupEvent` 后调用 `idMapped()` 通知核心物品 ID 已确定（核心内部用 int 物品 ID 而不是对象）。

**Java 对应：** 静态注册（`DeferredRegister`）+ datagen 更简单；如果一定要"配置决定物品"，就在 `RegisterEvent` 里读配置注册，模型用 `AddPackFindersEvent` 加一个内存 `PackResources`。

---

## 10. 跨模组集成方式

| 模组 | 做法（确认） |
| --- | --- |
| AE2 | 为每根曲线 ME 电缆两端创建 `IManagedGridNode`（`GridHelper`），`addNodeConn` 连接两个节点、`addBlockConn` 接到相邻方块的网格节点；图标物品 `AEIconItem` 用 `CUSTOM_DATA` 存 `pipe`/`joint` 决定渲染；支持所有 `AEColor` 和 glass/covered/smart/dense 变体，quartz fiber 用 `addEnergyPair` |
| CC: Tweaked | 每根曲线电缆创建 `WiredNode`，`connectTo` 与相邻方块的 wired 元素相连；目前只能接普通电缆 |
| GTCEu | 读取材料的 `WireProperties`（电压/安培/每格损耗）、`FluidPipeProperties`（温度/材质限制，`shouldBurn` 判定烧毁）；用 GT 原物品副手放置；EU 传输走 `IEnergyContainer`（`GTEnergyWrapper` 包装以支持 amp 分流） |
| Storage Drawers | `ItemInv.StorageDrawers` 直接操作 `IDrawerGroup` |
| EMI / JEI / REI | GUI 实现 ghost ingredient 拖放到过滤槽（`ghostAreas()` 返回打包的矩形 `long[]`，`acceptGhost` 接收物品/流体），流体 tooltip 优先委托给三者 |
| Iris | 关闭扩展顶点格式、检测阴影通道 |
| Scena / Roots 等 | 运行时字节码补丁修正兼容问题（例如 Scena issue #8） |

所有第三方类都用 `try { ... } catch (LinkageError)` + `TRY_XXX` 标志做软依赖。

---

## 11. 给 Java 重写的对应关系速查

| Curvy Pipes 做法 | Java 直接对应 |
| --- | --- |
| Rust 核心 + JNI | 全部 Java；数学用 JOML |
| 运行时 ASM/JVMTI 补丁 | Mixin（`GameRenderer#pick`、`Entity#collide`、`Level#getSignal`） |
| 每维度 `SavedData` + protobuf `byte[]` | `SavedData` + `FriendlyByteBuf`/自定义编码 |
| 区块索引 + `ChunkWatchEvent` 按玩家同步 | 同样的事件，`Long2ObjectMap<List<Edge>>` |
| faer 稀疏 Cholesky 求曲线 | Catmull-Rom/Hermite；或离散弯曲能量最小化 + EJML/CG |
| parry3d GJK/EPA 碰撞 | 胶囊体 vs AABB 手写（线段到盒子最近距离 < 半径） |
| 核心生成 `MeshData` → `VertexBuffer` 缓存 | `BufferBuilder` → `MeshData` → `VertexBuffer.upload`，按边缓存 |
| GLSL 径向菜单 | `GuiGraphics.fill` 扇形近似或自定义 `ShaderInstance` |
| taffy 布局 GUI | 手写坐标或简单 flex 辅助类 |
| 配置驱动动态注册物品 | `DeferredRegister` 静态注册（或 `RegisterEvent` 动态） |
| 内存资源包提供物品模型 | datagen / 手写 JSON |
| 单一 `byte[]` payload | `CustomPacketPayload` + `StreamCodec` |
| Capability 快照去重（`ItemInv.read`） | 同样的思路：`(item, componentsPatch)` 去重后再分配 |

---

## 12. 逆向材料索引

- `reverse-engineering/curvy_pipes-1.15.8/java/`：Java 侧完整源码（本文件第 4、5、7、8、10 节的"确认"项主要来自这里）。
- `reverse-engineering/curvy_pipes-1.15.8/NATIVE_API.md`：63 个 JNI 方法签名与钩子类。
- `reverse-engineering/curvy_pipes-1.15.8/java-generated/`：8 个运行时生成类的可读版本。
- `reverse-engineering/curvy_pipes-1.15.8/reference/default.yaml`、`en_us.json`：配置格式与全部交互文案。
- `reverse-engineering/curvy_pipes-1.15.8/native/x64/strings.tsv`、`RUST_MODULES.md`：Rust 模块清单、协议/配置字段名、着色器源码。
- `reverse-engineering/curvy_pipes-1.15.8/rust/`：本次未完成的 Rust 重建骨架与索引（`module-functions.json` 把每个 Rust 模块映射到反编译函数），`rust/NOTES/` 下是子任务留下的中间分析数据，可删。
