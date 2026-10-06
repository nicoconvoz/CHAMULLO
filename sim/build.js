// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
// Builds lab.html: the lab page with the simulator core inlined, so it opens from anywhere (file, preview, artifact).
const fs = require('node:fs');
const path = require('node:path');

const src = fs.readFileSync(path.join(__dirname, 'lab.src.html'), 'utf8');
const core = fs.readFileSync(path.join(__dirname, 'core.js'), 'utf8');
if (!src.includes('/*__CORE__*/')) throw new Error('lab.src.html is missing the /*__CORE__*/ placeholder');
fs.writeFileSync(path.join(__dirname, 'lab.html'), src.replace('/*__CORE__*/', () => core));
console.log('lab.html built');
