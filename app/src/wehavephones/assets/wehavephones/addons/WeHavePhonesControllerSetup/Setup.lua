---------------------------------------------------------------
-- WeHavePhones Controller Setup
---------------------------------------------------------------
-- A fresh ConsolePort install starts with no active device and
-- no gamepad bindings, and opens its setup guide. On the first
-- login without an active ConsolePort device, this picks the
-- preset that matches the connected controller and applies the
-- choices the guide marks as recommended: left-hand modifiers,
-- stick clicks as mouse buttons, balanced movement and the
-- device's preset bindings. Installs that already have a
-- device, or real gamepad bindings, are left alone.
--
-- Installed and kept current by WeHavePhones
-- (addons/required/ControllerSetupAddon.java).

local ADDON = ...;
local FALLBACK_DEVICE = 'Xbox';
-- Fewer bound gamepad combinations than this is an empty layout:
-- ConsolePort itself binds the mouse-emulation stick clicks.
local MIN_REAL_BINDINGS = 8;

local VENDOR_SONY, VENDOR_NINTENDO, VENDOR_VALVE = 0x054C, 0x057E, 0x28DE;
local PS5_PRODUCTS = { [0x0CE6] = true, [0x0DF2] = true };

-- The guide's recommended choices, in the guide's order. Order
-- matters: ConsolePort clears any other emulation that already
-- uses the button being assigned.
local RECOMMENDED_CVARS = {
	{ 'GamePadEmulateShift',       'PADLSHOULDER' };
	{ 'GamePadEmulateCtrl',        'PADLTRIGGER'  };
	{ 'GamePadEmulateAlt',         'none'         };
	{ 'GamePadEmulateTapWindowMs', 350            };
	{ 'GamePadCursorLeftClick',    'PADLSTICK'    };
	{ 'GamePadCursorRightClick',   'PADRSTICK'    };
};
local RECOMMENDED_SETTINGS = {
	{ 'mvmtAnalog',            true };
	{ 'mvmtStrafeAngleTravel', 115  };
	{ 'mvmtStrafeAngleCombat', 115  };
};

local function Print(message, ...)
	print(('|cFFFFD100WeHavePhones:|r ' .. message):format(...))
end

local function DeviceNameFor(rawState)
	if not rawState then return end;
	local vendor, product = rawState.vendorID, rawState.productID;
	if vendor == VENDOR_SONY then
		return PS5_PRODUCTS[product] and 'PlayStation 5' or 'PlayStation 4';
	elseif vendor == VENDOR_NINTENDO then
		return 'Nintendo Switch Pro';
	elseif vendor == VENDOR_VALVE then
		return 'Steam Deck';
	end
end

local function DetectDevice(Gamepad)
	local ids = C_GamePad.GetAllDeviceIDs() or {};
	local active = C_GamePad.GetActiveDeviceID and C_GamePad.GetActiveDeviceID();
	if active and active >= 0 then
		table.insert(ids, 1, active)
	end
	for _, id in ipairs(ids) do
		local name = DeviceNameFor(C_GamePad.GetDeviceRawState(id));
		if name and Gamepad:GetDevice(name) then
			return Gamepad:GetDevice(name);
		end
	end
	return Gamepad:GetDevice(FALLBACK_DEVICE);
end

local function CountBindings(Gamepad)
	local count = 0;
	for _, set in pairs(Gamepad:GetBindings()) do
		for _ in pairs(set) do
			count = count + 1;
		end
	end
	return count;
end

local function CloseGuide()
	if ConsolePortConfig and ConsolePortConfig:IsShown() then
		ConsolePortConfig:Hide()
	end
end

local function Setup(db)
	local Gamepad = db.Gamepad;
	if Gamepad:GetActiveDevice() then return end;
	if not C_GamePad.IsEnabled() then return end;

	local device = DetectDevice(Gamepad);
	if not device then return end;

	for _, cvar in ipairs(RECOMMENDED_CVARS) do
		db:SetCVar(cvar[1], cvar[2])
	end
	for _, setting in ipairs(RECOMMENDED_SETTINGS) do
		db('Settings/' .. setting[1], setting[2])
	end
	device:ApplyPresetVars()
	device:Activate()
	CloseGuide()
	-- The guide enables the cursor on the next frame if it is still open.
	RunNextFrame(CloseGuide)

	WeHavePhonesControllerSetupDB.device = device.Name;
	WeHavePhonesControllerSetupDB.appliedAt = time();
	return device;
end

local function BindingSetLoaded()
	local set = GetCurrentBindingSet();
	return set == Enum.BindingSet.Account or set == Enum.BindingSet.Character;
end

-- Counting before the binding set loads would see an empty layout
-- even when the account has synced gamepad bindings.
local function ApplyPresetBindings(db, device)
	local bindings = CountBindings(db.Gamepad);
	if bindings < MIN_REAL_BINDINGS then
		db.Bindings:LoadPreset(device.Name)
		Print('set up your %s controller with ConsolePort\'s recommended layout. Change it any time in ConsolePort (/cp).', device.Name)
	else
		Print('activated ConsolePort for your %s controller and kept your %d gamepad bindings.', device.Name, bindings)
	end
end

local function Run(frame, db)
	local device = Setup(db);
	if not device then return end;
	if BindingSetLoaded() then
		return ApplyPresetBindings(db, device);
	end
	frame:RegisterEvent('UPDATE_BINDINGS')
	frame:SetScript('OnEvent', function(this)
		if not BindingSetLoaded() or InCombatLockdown() then return end;
		this:UnregisterAllEvents()
		ApplyPresetBindings(db, device)
	end)
end

local frame = CreateFrame('Frame')
frame:RegisterEvent('ADDON_LOADED')
frame:RegisterEvent('PLAYER_LOGIN')
frame:SetScript('OnEvent', function(self, event, name)
	if event == 'ADDON_LOADED' then
		if name == ADDON then
			WeHavePhonesControllerSetupDB = WeHavePhonesControllerSetupDB or {};
			self:UnregisterEvent('ADDON_LOADED')
		end
		return;
	end
	self:UnregisterEvent(event)
	local db = ConsolePort and ConsolePort.GetData and ConsolePort:GetData();
	if not db or not db.Gamepad or not db.Bindings then return end;
	if InCombatLockdown() then
		-- Bindings and cvars are protected in combat (login after a combat /reload).
		self:RegisterEvent('PLAYER_REGEN_ENABLED')
		self:SetScript('OnEvent', function(this)
			this:UnregisterAllEvents()
			Run(this, db)
		end)
		return;
	end
	Run(self, db)
end)

