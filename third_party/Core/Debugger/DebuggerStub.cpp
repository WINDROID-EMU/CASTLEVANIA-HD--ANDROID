#include "pch.h"
#include "Debugger/Debugger.h"
#include "Debugger/DebuggerFeatures.h"

Debugger::Debugger(Emulator* emu, IConsole* console)
{
    _emu = emu;
    _console = console;
}

Debugger::~Debugger()
{
}

void Debugger::Release()
{
}

void Debugger::ProcessEvent(EventType type, std::optional<CpuType> cpuType)
{
}

void Debugger::ProcessConfigChange()
{
}

void Debugger::Run()
{
}

void Debugger::PauseOnNextFrame()
{
}

void Debugger::Step(CpuType cpuType, int32_t stepCount, StepType type, BreakSource source)
{
}

bool Debugger::IsPaused()
{
    return false;
}

bool Debugger::IsExecutionStopped()
{
    return false;
}

bool Debugger::HasBreakRequest()
{
    return false;
}

void Debugger::BreakRequest(bool release)
{
}

void Debugger::ResetSuspendCounter()
{
}

void Debugger::SuspendDebugger(bool release)
{
}

void Debugger::BreakImmediately(CpuType sourceCpu, BreakSource source)
{
}

DebuggerFeatures Debugger::GetDebuggerFeatures(CpuType cpuType)
{
    return {};
}

BaseEventManager* Debugger::GetEventManager(CpuType cpuType)
{
    return nullptr;
}

IDebugger* Debugger::GetCpuDebugger(CpuType cpuType)
{
    return nullptr;
}

IDebugger* Debugger::GetMainDebugger()
{
    return nullptr;
}

FrozenAddressManager* Debugger::GetFrozenAddressManager(CpuType cpuType)
{
    return nullptr;
}

ITraceLogger* Debugger::GetTraceLogger(CpuType cpuType)
{
    return nullptr;
}

PpuTools* Debugger::GetPpuTools(CpuType cpuType)
{
    return nullptr;
}

CallstackManager* Debugger::GetCallstackManager(CpuType cpuType)
{
    return nullptr;
}
