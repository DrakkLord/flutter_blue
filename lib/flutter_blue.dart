// Copyright 2017, Paul DeMarco.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

library flutter_blue;

import 'dart:async';

import 'package:collection/collection.dart';
import 'package:convert/convert.dart';
import 'package:flutter/services.dart';
import 'package:flutter_blue/gen/flutter_blue.pbenum.dart';
import 'package:flutter_blue/src/errors.dart';
import 'package:meta/meta.dart';
import 'package:rxdart/rxdart.dart';

import 'gen/flutter_blue.pb.dart' as protos;

part 'src/bluetooth_device_common.dart';
part 'src/client/bluetooth_characteristic.dart';
part 'src/client/bluetooth_descriptor.dart';
part 'src/client/bluetooth_device.dart';
part 'src/client/bluetooth_service.dart';
part 'src/constants.dart';
part 'src/flutter_blue.dart';
part 'src/guid.dart';
part 'src/server/bluetooth_characteristic_server.dart';
part 'src/server/bluetooth_descriptor_server.dart';
part 'src/server/bluetooth_device_server.dart';
part 'src/server/bluetooth_server_device_container.dart';
part 'src/server/bluetooth_service_server.dart';
