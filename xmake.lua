---@diagnostic disable: undefined-global, undefined-field

local sim_dir = path.join(os.scriptdir(), "cdp-tests")
local olddir = os.curdir()

target("rtl", function()
    set_kind("phony")
    on_run(function()
        local build_dir = path.join("build", "rtl")
        local selected_test = os.getenv("IROM_BIN")

        if (selected_test == nil or selected_test == "") then
            selected_test = "and"
            cprint("${yellow underline}[WARNING]${clear} IROM_BIN is not set, defaulting to 'and'.")
        end

        os.addenvs({ IROM_BIN = selected_test })

        local rtl_opts = {
            "-Dirom.bin.name=" .. selected_test,
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

target("sim-build", function()
    set_kind("phony")
    on_run(function()
        local selected_test = os.getenv("IROM_BIN")
        if (selected_test == nil or selected_test == "") then
            selected_test = "and"
        end
        local testfile = path.join(sim_dir, "bin", selected_test .. ".bin")
        if (not os.isfile(testfile)) then
            raise("Simulation image not found: " .. testfile .. ". Please set IROM_BIN to a valid test name.")
        end

        local vsrc = os.files("build/rtl/*.sv")
        if (#vsrc == 0) then
            cprint("${yellow underline}[WARNING]${clear} RTL not found in build/rtl, running `xmake run rtl` first.")
            os.execv("xmake", { "run", "rtl" })
            vsrc = os.files("build/rtl/*.sv")
        end
        if (#vsrc == 0) then
            raise("No SystemVerilog files found in build/rtl. Please run `xmake run rtl` first.")
        end
        local csrc = {}
        local csrc_patterns = {
            "cdp-tests/golden_model/*.c",
            "cdp-tests/golden_model/stage/*.c",
            "cdp-tests/golden_model/peripheral/*.c",
            "cdp-tests/csrc/*.c",
            "cdp-tests/csrc/*.cpp"
        }
        for _, pattern in ipairs(csrc_patterns) do
            for _, file in ipairs(os.files(pattern)) do
                table.insert(csrc, file)
            end
        end

        local verilator_args = {
            "-cc",
            "--exe",
            "--build"
        }

        for _, file in ipairs(vsrc) do
            table.insert(verilator_args, file)
        end

        table.insert(verilator_args, "--top-module")
        table.insert(verilator_args, "SoC_Top")

        for _, file in ipairs(csrc) do
            table.insert(verilator_args, file)
        end

        table.insert(verilator_args, "--trace")
        table.insert(verilator_args, "-Wno-lint")
        table.insert(verilator_args, "-Wno-style")
        table.insert(verilator_args, "-Wno-TIMESCALEMOD")
        table.insert(verilator_args, "+define+PATH=" .. testfile)
        table.insert(verilator_args, "-CFLAGS")
        table.insert(verilator_args, "-DPATH=" .. testfile)
        table.insert(verilator_args, "-ISoC")
        table.insert(verilator_args, "-CFLAGS")
        table.insert(verilator_args, "-I" .. path.join(sim_dir, "golden_model", "include"))
        table.insert(verilator_args, "-Mdir")
        table.insert(verilator_args, "build/obj_dir")

        os.execv("verilator", verilator_args)
        
        cprint("${green underline}[INFO]${clear} Simulation build completed in build/obj_dir")
        os.cd(olddir)
    end)
end)

target("sim-run", function()
    set_kind("phony")
    on_run(function()
        local selected_test = os.getenv("IROM_BIN")
        if (selected_test == nil or selected_test == "") then
            selected_test = "and"
        end

        local sim_exe = path.join("build", "obj_dir", "VSoC_Top")
        if (not os.isfile(sim_exe)) then
            cprint("${yellow underline}[WARNING]${clear} Simulator not found, running `xmake run sim-build` first.")
            os.execv("xmake", { "run", "sim-build" })
        end
        if (not os.isfile(sim_exe)) then
            raise("Simulator binary not found: " .. sim_exe .. ". Please run `xmake run sim-build` first.")
        end

        os.mkdir(path.join("build", "waveform"))
        os.execv(sim_exe, { selected_test })
    end)
end)

target("sim-all", function()
    set_kind("phony")
    on_run(function()
        local bin_files = os.files("cdp-tests/bin/*.bin")
        table.sort(bin_files)

        if (#bin_files == 0) then
            raise("No bin files found in cdp-tests/bin")
        end

        local report_dir = path.join("build", "sim-all")
        os.mkdir(report_dir)
        os.mkdir(path.join("build", "waveform"))

        local passed = {}
        local failed = {}

        cprint("${cyan underline}[INFO]${clear} Running %d test programs from cdp-tests/bin", #bin_files)

        for _, bin_file in ipairs(bin_files) do
            local case_name = path.basename(bin_file)
            local log_file = path.join(report_dir, case_name .. ".log")
            local code_file = path.join(report_dir, case_name .. ".code")
            local cmd = string.format(
                "IROM_BIN=%q xmake run sim-run > %q 2>&1; echo $? > %q",
                case_name,
                log_file,
                code_file
            )
            os.execv("sh", { "-c", cmd })

            local exit_code_text = io.readfile(code_file) or "1"
            local exit_code = tonumber(exit_code_text:match("%d+")) or 1
            local log_text = io.readfile(log_file) or ""
            local pass_mark = (log_text:find("Test Point Pass!", 1, true) ~= nil)

            if (exit_code == 0 and pass_mark) then
                table.insert(passed, case_name)
            else
                table.insert(failed, case_name)
            end
        end

        local summary_lines = {}
        table.insert(summary_lines, "SIM-ALL SUMMARY")
        table.insert(summary_lines, string.format("Total: %d", #bin_files))
        table.insert(summary_lines, string.format("Passed: %d", #passed))
        table.insert(summary_lines, string.format("Failed: %d", #failed))
        table.insert(summary_lines, "")
        table.insert(summary_lines, "Passed:")
        if (#passed == 0) then
            table.insert(summary_lines, "- (none)")
        else
            for _, name in ipairs(passed) do
                table.insert(summary_lines, "- " .. name)
            end
        end
        table.insert(summary_lines, "")
        table.insert(summary_lines, "Failed:")
        if (#failed == 0) then
            table.insert(summary_lines, "- (none)")
        else
            for _, name in ipairs(failed) do
                table.insert(summary_lines, "- " .. name)
            end
        end

        local summary_file = path.join(report_dir, "summary.txt")
        io.writefile(summary_file, table.concat(summary_lines, "\n") .. "\n")

        cprint("${green underline}[INFO]${clear} Summary written to %s", summary_file)
        cprint("${green underline}[INFO]${clear} Passed (%d): %s", #passed, #passed > 0 and table.concat(passed, ", ") or "(none)")
        if #failed > 0 then
            cprint("${red underline}[INFO]${clear} Failed (%d): %s", #failed, table.concat(failed, ", "))
        else
            cprint("${green underline}[INFO]${clear} Failed (%d): %s", #failed, "(none)")
        end
        
        if (#failed > 0) then
            raise(string.format("sim-all finished with %d failed cases", #failed))
        end
    end)
end)

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
