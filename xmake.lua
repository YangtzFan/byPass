---@diagnostic disable: undefined-global, undefined-field

-- ============================================================================
-- RTL 生成：将 Chisel 源码编译为 SystemVerilog
-- IROM 指令内容不再在编译期加载，改为仿真运行时由 difftest 框架动态加载
-- ============================================================================
target("rtl", function()
    set_kind("phony")
    on_run(function()
        local build_dir = path.join("build", "rtl")

        local rtl_opts = {
            "-i",
            "chiselTemplate.runMain",
            "mycpu.GenerateVerilog",
            "--target",
            "systemverilog",
            "--split-verilog",
            "-td",
            build_dir
        }
        os.tryrm(build_dir)
        os.execv("mill", rtl_opts)
    end)
end)

target("comp", function()
    set_kind("phony")
    on_run(function()
        os.execv("mill", { "-i", "chiselTemplate.compile" })
    end)
end)

target("fmt", function()
    set_kind("phony")
    on_run(function()
        os.execv("mill", { "-i", "chiselTemplate.reformat" })
    end)
end)

target("clean", function()
    set_kind("phony")
    on_run(function()
        os.rmdir(path.join("build"))
    end)
end)

-- ============================================================================
-- yosys-sta 后端流程：综合 (Yosys) + 静态时序分析 (iSTA) + 功耗分析 (iPA)
-- 工具链与脚本位于 tools/yosys-sta/，等价于原仓库 Makefile 的 init/syn/sta/clean。
--
-- 默认参数（均可通过环境变量覆盖）：
--   DESIGN         = MyCPU                       顶层模块名
--   PDK            = icsprout55                  工艺库
--   CLK_PORT_NAME  = clock                       时钟端口（byPass 顶层为 clock）
--   CLK_FREQ_MHZ   = 500                         目标频率
--   SDC_FILE       = tools/yosys-sta/scripts/default.sdc
--   RTL_FILES      = build/rtl/*.sv              空格分隔的 RTL 列表
--   O              = build/sta                   结果输出根目录
--
-- 用法：
--   xmake run sta-init     # 一次性下载预编译 iEDA + 克隆 icsprout55 PDK
--   xmake run sta-syn      # 仅跑 yosys 综合
--   xmake run sta          # 跑 syn + iSTA + iPA
--   xmake run sta-clean    # 清理 build/sta 与 tools/yosys-sta/result
-- ============================================================================

-- 工具链根目录（Lua 闭包内 path.join 需要绝对路径，通过 os.scriptdir() 获取）
local function _sta_tools_dir()
    return path.join(os.scriptdir(), "tools", "yosys-sta")
end

-- 解析常用环境变量，返回综合 / STA 共用的参数表
local function _sta_params()
    local p = {}
    p.tools     = _sta_tools_dir()
    p.design    = os.getenv("DESIGN")        or "MyCPU"
    p.pdk       = os.getenv("PDK")           or "icsprout55"
    p.clk_port  = os.getenv("CLK_PORT_NAME") or "clock"
    p.clk_freq  = os.getenv("CLK_FREQ_MHZ")  or "500"
    p.sdc_file  = os.getenv("SDC_FILE")
                  or path.join(p.tools, "scripts", "default.sdc")
    p.out_root  = os.getenv("O")
                  or path.join(os.scriptdir(), "build", "sta")
    p.result_dir = path.join(p.out_root, p.design .. "-" .. p.clk_freq .. "MHz")
    p.netlist    = path.join(p.result_dir, p.design .. ".netlist.v")

    -- RTL 文件列表：环境变量优先；否则取 build/rtl/*.sv
    local rtl_env = os.getenv("RTL_FILES")
    if rtl_env and rtl_env ~= "" then
        p.rtl_files = rtl_env
    else
        local files = os.files(path.join(os.scriptdir(), "build", "rtl", "*.sv"))
        p.rtl_files = table.concat(files, " ")
    end
    return p
end

-- 一次性环境初始化：下载预编译 iEDA + 拉取 icsprout55 PDK
target("sta-init", function()
    set_kind("phony")
    set_default(false)
    on_run(function()
        local tools = _sta_tools_dir()
        if not os.isdir(tools) then
            raise("tools/yosys-sta 目录不存在")
        end
        os.cd(tools)

        cprint("${green underline}[INFO]${clear} 下载预编译 iEDA ...")
        os.exec('bash -c "$(wget -O - https://ysyx.oscc.cc/slides/resources/scripts/init-yosys-sta.sh)"')

        os.mkdir("pdk")
        if not os.isdir(path.join("pdk", "icsprout55")) then
            cprint("${green underline}[INFO]${clear} 克隆 icsprout55 PDK ...")
            os.cd("pdk")
            os.exec("git clone -b ysyx --depth 1 git@github.com:openecos-projects/icsprout55-pdk.git icsprout55")
        else
            cprint("${yellow underline}[INFO]${clear} icsprout55 已存在，跳过克隆")
        end

        cprint("${green underline}[INFO]${clear} 校验 iEDA ...")
        os.exec("bash -c 'echo exit | ./bin/iEDA -v'")
    end)
end)

-- 仅跑 yosys 综合，产出 <DESIGN>.netlist.v / synth_stat.txt / synth_check.txt
target("sta-syn", function()
    set_kind("phony")
    set_default(false)
    on_run(function()
        local p = _sta_params()
        if p.rtl_files == "" then
            raise("找不到 RTL 文件，请先 `xmake run rtl` 或显式设置 RTL_FILES")
        end
        os.mkdir(p.result_dir)

        local yosys_tcl = path.join(p.tools, "scripts", "yosys.tcl")
        local yosys_log = path.join(p.result_dir, "yosys.log")

        -- 透传 CLK_FREQ_MHZ / CLK_PORT_NAME 给 yosys.tcl
        local envs = {
            CLK_FREQ_MHZ  = p.clk_freq,
            CLK_PORT_NAME = p.clk_port,
        }

        -- yosys 通过 stdin 接收脚本指令（与原 Makefile 一致）
        local cmd = string.format(
            'echo tcl %s %s %s "%s" %s | yosys -g -l %s -s -',
            yosys_tcl, p.design, p.pdk, p.rtl_files, p.netlist, yosys_log)
        cprint("${green underline}[SYN]${clear} %s", cmd)
        os.execv("bash", {"-c", cmd}, {envs = envs})
    end)
end)

-- 完整后端：先综合，再用 iSTA/iPA 跑时序与功耗
target("sta", function()
    set_kind("phony")
    set_default(false)
    add_deps("sta-syn")
    on_run(function()
        local p = _sta_params()
        local sta_tcl = path.join(p.tools, "scripts", "sta.tcl")
        local ieda    = path.join(p.tools, "bin", "iEDA")
        local sta_log = path.join(p.result_dir, "sta.log")

        if not os.isfile(ieda) then
            raise("未找到 iEDA：" .. ieda .. "，请先执行 `xmake run sta-init`")
        end
        if not os.isfile(p.sdc_file) then
            raise("SDC 文件不存在：" .. p.sdc_file)
        end

        local envs = {
            CLK_FREQ_MHZ  = p.clk_freq,
            CLK_PORT_NAME = p.clk_port,
        }

        -- 与原 Makefile 等价：./bin/iEDA -script sta.tcl <sdc> <netlist> <design> <pdk>
        local cmd = string.format(
            'set -o pipefail && %s -script %s %s %s %s %s 2>&1 | tee %s',
            ieda, sta_tcl, p.sdc_file, p.netlist, p.design, p.pdk, sta_log)
        cprint("${green underline}[STA]${clear} %s", cmd)
        os.execv("bash", {"-c", cmd}, {envs = envs})

        cprint("${green underline}[DONE]${clear} 报告位于 %s", p.result_dir)
    end)
end)

-- 清理后端产物（不影响 RTL / Chisel 构建）
target("sta-clean", function()
    set_kind("phony")
    set_default(false)
    on_run(function()
        os.rmdir(path.join(os.scriptdir(), "build", "sta"))
        os.rmdir(path.join(_sta_tools_dir(), "result"))
        cprint("${green underline}[INFO]${clear} 已清理 build/sta 与 tools/yosys-sta/result")
    end)
end)

target("view", function () 
    set_kind("phony")
    on_run(function ()
        local infiles = os.files("build/rtl/*.sv")
        local outfiles = {}

        for _, file in ipairs(infiles) do
            table.insert(outfiles, path.absolute(file))
        end

        os.mkdir("build")
        io.writefile("build/rtl/filelist.f",
            #outfiles > 0 and (table.concat(outfiles, "\n") .. "\n") or "")

        cprint("${green underline}[INFO]${clear} generated: build/rtl/filelist.f")
        os.exec("hier-viewer -f build/rtl/filelist.f --preview --preview-host 0.0.0.0 --output out/")
    end)
end)

-- ============================================================================
-- 初始化子模块
-- ============================================================================
target("init", function()
    set_kind("phony")
    set_default(false)
    local function isempty(v)
        return v == nil or v == ""
    end
    local default_proxy = os.getenv("default_proxy_LAN")
    local http          = os.getenv("http_proxy")
    local https         = os.getenv("https_proxy")
    local isProxyEmpty  = isempty(http) or isempty(https)
    local autoSetProxy  = false

    before_run(function() -- Check whether the environment variables about system proxy is OK or not.
        if (isProxyEmpty) then
            cprint("${yellow underline}[WARNING]${clear} http_proxy and https_proxy have not been set.")
            if (isempty(default_proxy)) then
                cprint("${red underline}[SEVERE]${clear} There are no proxy set. Initialization operation failed.")
                local msg = format("Initialization failed")
                raise(msg)
            else
                autoSetProxy = true
            end
        end
    end)

    on_run(function()
        if (autoSetProxy) then
            local envs          = {}
            envs["http_proxy"]  = default_proxy
            envs["https_proxy"] = default_proxy
            os.addenvs(envs)
            cprint("${green underline}[INFO]${clear} Default proxy has been set. Proxy has been configured automatically.")
        end
        cprint("${green underline}[INFO]${clear} Updating submodules in this repo... This may take a few seconds.")
        os.cd(os.scriptdir())
        os.exec("git submodule update --init")
    end)
end)
