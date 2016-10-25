package org.genivi.sota.device_registry

import akka.http.scaladsl.model.StatusCodes._
import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser._
import org.genivi.sota.data.{GroupInfo, Uuid}
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen
import org.scalatest.FunSuite
import org.scalatest.concurrent.Eventually.eventually


class GroupsResourceSpec extends FunSuite with ResourceSpec {

  private def createSystemInfoOk(uuid: Uuid, systemInfo: Json) = {
    createSystemInfo(uuid, systemInfo) ~> route ~> check {
      status shouldBe Created
    }
  }

  val complexJsonArray =
    Json.arr(Json.fromFields(List(("key", Json.fromString("value")), ("type", Json.fromString("fish")))))
  val complexNumericJsonArray =
    Json.arr(Json.fromFields(List(("key", Json.fromString("value")), ("type", Json.fromInt(5)))))

  val groupInfo = parse("""{"cat":"dog","fish":{"cow":1}}""").toOption.get
  val systemInfos = Seq(
    parse("""{"cat":"dog","fish":{"cow":1,"sheep":[42,"penguin",23]}}"""), // device #1
    parse("""{"cat":"dog","fish":{"cow":1,"sloth":true},"antilope":"bison"}"""), // device #2
    parse("""{"fish":{"cow":1},"cat":"dog"}"""), // matches without discarding
    parse("""{"fish":{"cow":1,"sloth":false},"antilope":"emu","cat":"dog"}"""), // matches with discarding
    parse("""{"cat":"liger","fish":{"cow":1}}"""), // doesn't match without discarding
    parse("""{"cat":"dog","fish":{"cow":1},"bison":17}"""), // doesn't match without discarding
    parse("""{"cat":"dog","fish":{"cow":2,"sheep":false},"antilope":"emu"}""") // doesn't match with discarding
    ).map(_.toOption.get)

  test("GET all groups lists all groups") {
    //TODO: PRO-1182 turn this back into a property when we can delete groups
    val groups = Gen.resize(10, arbitrary[Set[GroupInfo]]).sample.get
    groups.foreach { case GroupInfo(id, groupName, namespace, groupInfo, discardedAttrs) =>
      createGroupInfoOk(id, groupName, groupInfo)
    }

    listGroups() ~> route ~> check {
      status shouldBe OK
      // invoking createGroupInfo routes directly disregards discarded attributes
      responseAs[Set[GroupInfo]].map(_.copy(discardedAttrs = Json.Null)) shouldBe
        groups.map(_.copy(discardedAttrs = Json.Null))
    }
  }

  test("creating groups is possible") {

    val complexJsonObj = Json.fromFields(List(("key", Json.fromString("value")), ("type", Json.fromString("fish"))))
    val complexNumericJsonObj = Json.fromFields(List(("key", Json.fromString("value")), ("type", Json.fromInt(5))))

    val groupName = arbitrary[GroupInfo.Name].sample.get

    val device1 = createDeviceOk(genDeviceT.sample.get)
    val device2 = createDeviceOk(genDeviceT.sample.get)

    createSystemInfoOk(device1, complexJsonObj)
    createSystemInfoOk(device2, complexNumericJsonObj)

    createGroupFromDevices(device1, device2, groupName) ~> route ~> check {
      status shouldBe OK
      val groupId = responseAs[Uuid]
      eventually {
        listDevicesInGroup(groupId) ~> route ~> check {
          status shouldBe OK
          responseAs[Set[Uuid]] should contain allOf(device1, device2)
        }
        listGroupsForDevice(device1) ~> route ~> check {
          status shouldBe OK
          responseAs[Set[Uuid]] should contain(groupId)
        }
        listGroupsForDevice(device2) ~> route ~> check {
          status shouldBe OK
          responseAs[Set[Uuid]] should contain(groupId)
        }
        countDevicesInGroup(groupId) ~> route ~> check {
          status shouldBe OK
          responseAs[Int] shouldEqual 2
        }
      }
    }

    deleteDeviceOk(device1)
    deleteDeviceOk(device2)
  }

  test("GET /group_info request fails on non-existent device") {
    val id = genUuid.sample.get
    fetchGroupInfo(id) ~> route ~> check {
      status shouldBe NotFound
    }
  }

  test("GET group_info after POST should return what was posted.") {
    val group = genGroupInfo.sample.get
    createGroupInfoOk(group.id, group.groupName, group.groupInfo)

    fetchGroupInfo(group.id) ~> route ~> check {
      status shouldBe OK
      val json2: Json = responseAs[Json]
      group.groupInfo shouldEqual json2
    }
  }

  test("GET group_info after PUT should return what was updated.") {
    val id = genUuid.sample.get
    val groupName = genGroupName.sample.get
    val json1 = simpleJsonGen.sample.get
    val json2 = simpleJsonGen.sample.get

    createGroupInfoOk(id, groupName, json1)

    updateGroupInfo(id, groupName, json2) ~> route ~> check {
      status shouldBe OK
    }

    fetchGroupInfo(id) ~> route ~> check {
      status shouldBe OK
      val json3: Json = responseAs[Json]
      json2 shouldBe json3
    }
  }

  test("Renaming groups") {
    val group = genGroupInfo.sample.get
    val newGroupName = genGroupName.sample.get
    createGroupInfoOk(group.id, group.groupName, group.groupInfo)

    renameGroup(group.id, newGroupName) ~> route ~> check {
      status shouldBe OK
    }

    listGroups() ~> route ~> check {
      status shouldBe OK
      val groups = responseAs[Seq[GroupInfo]]
      groups.count(e => e.id.equals(group.id) && e.groupName.equals(newGroupName)) shouldBe 1
    }
  }

  test("counting devices should fail for non-existent groups") {
    countDevicesInGroup(genUuid.sample.get) ~> route ~> check {
      status shouldBe NotFound
    }
  }

  test("match devices to an existing group") {
    val devices@Seq(d1, d2, d3, d4, d5, d6, d7) =
      genConflictFreeDeviceTs(7).sample.get.map(createDeviceOk(_))
    devices.zip(systemInfos).foreach { case (d, si) => createSystemInfoOk(d, si) }

    createGroupFromDevices(d1, d2, arbitrary[GroupInfo.Name].sample.get) ~> route ~> check {
      status shouldBe OK
      val groupId = responseAs[Uuid]
      fetchGroupInfo(groupId) ~> route ~> check {
        status shouldBe OK
        responseAs[Json] shouldBe groupInfo
      }
      eventually {
        listDevicesInGroup(groupId) ~> route ~> check {
          status shouldBe OK
          val r = responseAs[Seq[Uuid]]
          r should contain (d3)
          r should contain (d4)
          r should not contain (d5)
          r should not contain (d6)
          r should not contain (d7)
        }
      }
    }

    devices.foreach(deleteDeviceOk(_))
  }

  test("updating system info for device updates group membership") {
    val devices@Seq(d1, d2, _, _, d5) =
      genConflictFreeDeviceTs(5).sample.get.map(createDeviceOk(_))
    devices.zip(systemInfos).foreach { case (d, si) => createSystemInfoOk(d, si) }

    createGroupFromDevices(d1, d2, arbitrary[GroupInfo.Name].sample.get) ~> route ~> check {
      status shouldBe OK
      val groupId = responseAs[Uuid]
      fetchGroupInfo(groupId) ~> route ~> check {
        status shouldBe OK
        responseAs[Json] shouldBe groupInfo
      }
      updateSystemInfo(d5, groupInfo) ~> route ~> check {
        status shouldBe OK
      }
      eventually {
        listDevicesInGroup(groupId) ~> route ~> check {
          status shouldBe OK
          responseAs[Seq[Uuid]] should contain (d5)
        }
      }
    }

    devices.foreach(deleteDeviceOk(_))
  }

  test("adding devices to groups") {
    val group = genGroupInfo.sample.get
    val deviceId = createDeviceOk(genDeviceT.sample.get)
    createGroupInfo(group.id, group.groupName, group.groupInfo) ~> route ~> check {
      status shouldBe Created
    }

    addDeviceToGroup(group.id, deviceId) ~> route ~> check {
      status shouldBe OK
    }

    listDevicesInGroup(group.id) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[Seq[Uuid]]
      devices.contains(deviceId) shouldBe true
    }

    deleteDeviceOk(deviceId)
  }

  test("removing devices from groups") {
    val group = genGroupInfo.sample.get
    val deviceId = createDeviceOk(genDeviceT.sample.get)
    createGroupInfo(group.id, group.groupName, group.groupInfo) ~> route ~> check {
      status shouldBe Created
    }

    addDeviceToGroup(group.id, deviceId) ~> route ~> check {
      status shouldBe OK
    }

    listDevicesInGroup(group.id) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[Seq[Uuid]]
      devices.contains(deviceId) shouldBe true
    }

    removeDeviceFromGroup(group.id, deviceId) ~> route ~> check {
      status shouldBe OK
    }

    listDevicesInGroup(group.id) ~> route ~> check {
      status shouldBe OK
      val devices = responseAs[Seq[Uuid]]
      devices.contains(deviceId) shouldBe false
    }

    deleteDeviceOk(deviceId)
  }

  test("updating system info for device updates blah membership") {
    val json1 = parse("{\"id\":\"computer\",\"id-nr\":\"0\",\"class\":\"system\",\"claimed\":true,\"description\":\"Computer\",\"width\":64,\"capabilities\":{\"smbios-2.5\":\"SMBIOS version 2.5\",\"vsyscall32\":\"32-bit processes\"},\"children\":[{\"id\":\"core\",\"id-nr\":\"1\",\"class\":\"bus\",\"claimed\":true,\"description\":\"Motherboard\",\"physid\":\"0\",\"children\":[{\"id\":\"memory\",\"id-nr\":\"2\",\"class\":\"memory\",\"claimed\":true,\"description\":\"System memory\",\"physid\":\"0\",\"units\":\"bytes\",\"size\":2057728000},{\"id\":\"cpu\",\"id-nr\":\"3\",\"class\":\"processor\",\"claimed\":true,\"product\":\"Intel(R) Core(TM) i7-5650U CPU @ 2.20GHz\",\"vendor\":\"Intel Corp.\",\"physid\":\"1\",\"businfo\":\"cpu@0\",\"width\":64,\"capabilities\":{\"fpu\":\"mathematical co-processor\",\"fpu_exception\":\"FPU exceptions reporting\",\"wp\":true,\"vme\":\"virtual mode extensions\",\"de\":\"debugging extensions\",\"pse\":\"page size extensions\",\"tsc\":\"time stamp counter\",\"msr\":\"model-specific registers\",\"pae\":\"4GB+ memory addressing (Physical Address Extension)\",\"mce\":\"machine check exceptions\",\"cx8\":\"compare and exchange 8-byte\",\"apic\":\"on-chip advanced programmable interrupt controller (APIC)\",\"sep\":\"fast system calls\",\"mtrr\":\"memory type range registers\",\"pge\":\"page global enable\",\"mca\":\"machine check architecture\",\"cmov\":\"conditional move instruction\",\"pat\":\"page attribute table\",\"pse36\":\"36-bit page size extensions\",\"clflush\":true,\"mmx\":\"multimedia extensions (MMX)\",\"fxsr\":\"fast floating point save/restore\",\"sse\":\"streaming SIMD extensions (SSE)\",\"sse2\":\"streaming SIMD extensions (SSE2)\",\"syscall\":\"fast system calls\",\"nx\":\"no-execute bit (NX)\",\"rdtscp\":true,\"x86-64\":\"64bits extensions (x86-64)\",\"constant_tsc\":true,\"rep_good\":true,\"nopl\":true,\"xtopology\":true,\"nonstop_tsc\":true,\"pni\":true,\"pclmulqdq\":true,\"monitor\":true,\"ssse3\":true,\"cx16\":true,\"sse4_1\":true,\"sse4_2\":true,\"movbe\":true,\"popcnt\":true,\"aes\":true,\"xsave\":true,\"avx\":true,\"rdrand\":true,\"hypervisor\":true,\"lahf_lm\":true,\"abm\":true,\"3dnowprefetch\":true,\"rdseed\":true}},{\"id\":\"pci\",\"id-nr\":\"4\",\"class\":\"bridge\",\"claimed\":true,\"handle\":\"PCIBUS:0000:00\",\"description\":\"Host bridge\",\"product\":\"440FX - 82441FX PMC [Natoma]\",\"vendor\":\"Intel Corporation\",\"physid\":\"100\",\"businfo\":\"pci@0000:00:00.0\",\"version\":\"02\",\"width\":32,\"clock\":33000000,\"children\":[{\"id\":\"isa\",\"id-nr\":\"5\",\"class\":\"bridge\",\"claimed\":true,\"handle\":\"PCI:0000:00:01.0\",\"description\":\"ISA bridge\",\"product\":\"82371SB PIIX3 ISA [Natoma/Triton II]\",\"vendor\":\"Intel Corporation\",\"physid\":\"1\",\"businfo\":\"pci@0000:00:01.0\",\"version\":\"00\",\"width\":32,\"clock\":33000000,\"configuration\":{\"latency\":\"0\"},\"capabilities\":{\"isa\":true,\"bus_master\":\"bus mastering\"}},{\"id\":\"ide\",\"id-nr\":\"6\",\"class\":\"storage\",\"claimed\":true,\"handle\":\"PCI:0000:00:01.1\",\"description\":\"IDE interface\",\"product\":\"82371AB/EB/MB PIIX4 IDE\",\"vendor\":\"Intel Corporation\",\"physid\":\"1.1\",\"businfo\":\"pci@0000:00:01.1\",\"version\":\"01\",\"width\":32,\"clock\":33000000,\"configuration\":{\"driver\":\"ata_piix\",\"latency\":\"64\"},\"capabilities\":{\"ide\":true,\"bus_master\":\"bus mastering\"}},{\"id\":\"display\",\"id-nr\":\"7\",\"class\":\"display\",\"handle\":\"PCI:0000:00:02.0\",\"description\":\"VGA compatible controller\",\"product\":\"VirtualBox Graphics Adapter\",\"vendor\":\"InnoTek Systemberatung GmbH\",\"physid\":\"2\",\"businfo\":\"pci@0000:00:02.0\",\"version\":\"00\",\"width\":32,\"clock\":33000000,\"configuration\":{\"latency\":\"0\"},\"capabilities\":{\"vga_controller\":true,\"bus_master\":\"bus mastering\"}},{\"id\":\"network\",\"id-nr\":\"8\",\"class\":\"network\",\"claimed\":true,\"handle\":\"PCI:0000:00:03.0\",\"description\":\"Ethernet interface\",\"product\":\"82540EM Gigabit Ethernet Controller\",\"vendor\":\"Intel Corporation\",\"physid\":\"3\",\"businfo\":\"pci@0000:00:03.0\",\"logicalname\":\"eth0\",\"version\":\"02\",\"serial\":\"[REMOVED]\",\"units\":\"bit/s\",\"size\":1000000000,\"capacity\":1000000000,\"width\":32,\"clock\":66000000,\"configuration\":{\"autonegotiation\":\"on\",\"broadcast\":\"yes\",\"driver\":\"e1000\",\"driverversion\":\"7.3.21-k8-NAPI\",\"duplex\":\"full\",\"ip\":\"[REMOVED]\",\"latency\":\"64\",\"link\":\"yes\",\"mingnt\":\"255\",\"multicast\":\"yes\",\"port\":\"twisted pair\",\"speed\":\"1Gbit/s\"},\"capabilities\":{\"pm\":\"Power Management\",\"pcix\":\"PCI-X\",\"bus_master\":\"bus mastering\",\"cap_list\":\"PCI capabilities listing\",\"ethernet\":true,\"physical\":\"Physical interface\",\"tp\":\"twisted pair\",\"10bt\":\"10Mbit/s\",\"10bt-fd\":\"10Mbit/s (full duplex)\",\"100bt\":\"100Mbit/s\",\"100bt-fd\":\"100Mbit/s (full duplex)\",\"1000bt-fd\":\"1Gbit/s (full duplex)\",\"autonegotiation\":\"Auto-negotiation\"}},{\"id\":\"generic\",\"id-nr\":\"9\",\"class\":\"generic\",\"claimed\":true,\"handle\":\"PCI:0000:00:04.0\",\"description\":\"System peripheral\",\"product\":\"VirtualBox Guest Service\",\"vendor\":\"InnoTek Systemberatung GmbH\",\"physid\":\"4\",\"businfo\":\"pci@0000:00:04.0\",\"version\":\"00\",\"width\":32,\"clock\":33000000,\"configuration\":{\"driver\":\"vboxguest\",\"latency\":\"0\"},\"capabilities\":{\"bus_master\":\"bus mastering\"}},{\"id\":\"bridge\",\"id-nr\":\"10\",\"class\":\"bridge\",\"handle\":\"PCI:0000:00:07.0\",\"description\":\"Bridge\",\"product\":\"82371AB/EB/MB PIIX4 ACPI\",\"vendor\":\"Intel Corporation\",\"physid\":\"7\",\"businfo\":\"pci@0000:00:07.0\",\"version\":\"08\",\"width\":32,\"clock\":33000000,\"configuration\":{\"latency\":\"0\"},\"capabilities\":{\"bridge\":true,\"bus_master\":\"bus mastering\"}}]},{\"id\":\"scsi\",\"id-nr\":\"11\",\"class\":\"storage\",\"claimed\":true,\"physid\":\"2\",\"logicalname\":\"scsi0\",\"capabilities\":{\"emulated\":\"Emulated device\"},\"children\":[{\"id\":\"disk\",\"id-nr\":\"12\",\"class\":\"disk\",\"claimed\":true,\"handle\":\"SCSI:00:00:00:00\",\"description\":\"ATA Disk\",\"product\":\"VBOX HARDDISK\",\"physid\":\"0.0.0\",\"businfo\":\"scsi@0:0.0.0\",\"logicalname\":\"/dev/sda\",\"dev\":\"8:0\",\"version\":\"1.0\",\"serial\":\"[REMOVED]\",\"units\":\"bytes\",\"size\":10632560640,\"configuration\":{\"ansiversion\":\"5\",\"logicalsectorsize\":\"512\",\"sectorsize\":\"512\",\"signature\":\"c1250823\"},\"capabilities\":{\"partitioned\":\"Partitioned disk\",\"partitioned:dos\":\"MS-DOS partition table\"},\"children\":[{\"id\":\"volume:0\",\"id-nr\":\"13\",\"class\":\"volume\",\"claimed\":true,\"description\":\"EXT4 volume\",\"vendor\":\"Linux\",\"physid\":\"1\",\"businfo\":\"scsi@0:0.0.0,1\",\"logicalname\":[\"/dev/sda1\",\"/\"],\"dev\":\"8:1\",\"version\":\"1.0\",\"serial\":\"[REMOVED]\",\"size\":10144972800,\"capacity\":10144972800,\"configuration\":{\"created\":\"2015-10-21 18:04:06\",\"filesystem\":\"ext4\",\"lastmountpoint\":\"/\",\"modified\":\"2016-10-24 09:05:29\",\"mount.fstype\":\"ext4\",\"mount.options\":\"rw,relatime,errors=remount-ro,data=ordered\",\"mounted\":\"2016-09-30 14:57:31\",\"state\":\"mounted\"},\"capabilities\":{\"primary\":\"Primary partition\",\"bootable\":\"Bootable partition (active)\",\"journaled\":true,\"extended_attributes\":\"Extended Attributes\",\"large_files\":\"4GB+ files\",\"huge_files\":\"16TB+ files\",\"dir_nlink\":\"directories with 65000+ subdirs\",\"extents\":\"extent-based allocation\",\"ext4\":true,\"ext2\":\"EXT2/EXT3\",\"initialized\":\"initialized volume\"}},{\"id\":\"volume:1\",\"id-nr\":\"14\",\"class\":\"volume\",\"claimed\":true,\"description\":\"Extended partition\",\"physid\":\"2\",\"businfo\":\"scsi@0:0.0.0,2\",\"logicalname\":\"/dev/sda2\",\"dev\":\"8:2\",\"size\":484443136,\"capacity\":484443136,\"capabilities\":{\"primary\":\"Primary partition\",\"extended\":\"Extended partition\",\"partitioned\":\"Partitioned disk\",\"partitioned:extended\":\"Extended partition\"},\"children\":[{\"id\":\"logicalvolume\",\"id-nr\":\"15\",\"class\":\"volume\",\"claimed\":true,\"description\":\"Linux swap / Solaris partition\",\"physid\":\"5\",\"logicalname\":\"/dev/sda5\",\"dev\":\"8:5\",\"capacity\":484442112,\"capabilities\":{\"nofs\":\"No filesystem\"}}]}]}]}]}]}").toOption.get
    val json2 = parse("{\"id\":\"computer\",\"id-nr\":\"0\",\"class\":\"system\",\"claimed\":true,\"description\":\"Computer\",\"width\":64,\"capabilities\":{\"smbios-2.5\":\"SMBIOS version 2.5\",\"vsyscall32\":\"32-bit processes\"},\"children\":[{\"id\":\"core\",\"id-nr\":\"1\",\"class\":\"bus\",\"claimed\":true,\"description\":\"Motherboard\",\"physid\":\"0\",\"children\":[{\"id\":\"memory\",\"id-nr\":\"2\",\"class\":\"memory\",\"claimed\":true,\"description\":\"System memory\",\"physid\":\"0\",\"units\":\"bytes\",\"size\":2057728000},{\"id\":\"cpu\",\"id-nr\":\"3\",\"class\":\"processor\",\"claimed\":true,\"product\":\"Intel(R) Core(TM) i7-5650U CPU @ 2.20GHz\",\"vendor\":\"Intel Corp.\",\"physid\":\"1\",\"businfo\":\"cpu@0\",\"width\":64,\"capabilities\":{\"fpu\":\"mathematical co-processor\",\"fpu_exception\":\"FPU exceptions reporting\",\"wp\":true,\"vme\":\"virtual mode extensions\",\"de\":\"debugging extensions\",\"pse\":\"page size extensions\",\"tsc\":\"time stamp counter\",\"msr\":\"model-specific registers\",\"pae\":\"4GB+ memory addressing (Physical Address Extension)\",\"mce\":\"machine check exceptions\",\"cx8\":\"compare and exchange 8-byte\",\"apic\":\"on-chip advanced programmable interrupt controller (APIC)\",\"sep\":\"fast system calls\",\"mtrr\":\"memory type range registers\",\"pge\":\"page global enable\",\"mca\":\"machine check architecture\",\"cmov\":\"conditional move instruction\",\"pat\":\"page attribute table\",\"pse36\":\"36-bit page size extensions\",\"clflush\":true,\"mmx\":\"multimedia extensions (MMX)\",\"fxsr\":\"fast floating point save/restore\",\"sse\":\"streaming SIMD extensions (SSE)\",\"sse2\":\"streaming SIMD extensions (SSE2)\",\"syscall\":\"fast system calls\",\"nx\":\"no-execute bit (NX)\",\"rdtscp\":true,\"x86-64\":\"64bits extensions (x86-64)\",\"constant_tsc\":true,\"rep_good\":true,\"nopl\":true,\"xtopology\":true,\"nonstop_tsc\":true,\"pni\":true,\"pclmulqdq\":true,\"monitor\":true,\"ssse3\":true,\"cx16\":true,\"sse4_1\":true,\"sse4_2\":true,\"movbe\":true,\"popcnt\":true,\"aes\":true,\"xsave\":true,\"avx\":true,\"rdrand\":true,\"hypervisor\":true,\"lahf_lm\":true,\"abm\":true,\"3dnowprefetch\":true,\"rdseed\":true}},{\"id\":\"pci\",\"id-nr\":\"4\",\"class\":\"bridge\",\"claimed\":true,\"handle\":\"PCIBUS:0000:00\",\"description\":\"Host bridge\",\"product\":\"440FX - 82441FX PMC [Natoma]\",\"vendor\":\"Intel Corporation\",\"physid\":\"100\",\"businfo\":\"pci@0000:00:00.0\",\"version\":\"02\",\"width\":32,\"clock\":33000000,\"children\":[{\"id\":\"isa\",\"id-nr\":\"5\",\"class\":\"bridge\",\"claimed\":true,\"handle\":\"PCI:0000:00:01.0\",\"description\":\"ISA bridge\",\"product\":\"82371SB PIIX3 ISA [Natoma/Triton II]\",\"vendor\":\"Intel Corporation\",\"physid\":\"1\",\"businfo\":\"pci@0000:00:01.0\",\"version\":\"00\",\"width\":32,\"clock\":33000000,\"configuration\":{\"latency\":\"0\"},\"capabilities\":{\"isa\":true,\"bus_master\":\"bus mastering\"}},{\"id\":\"ide\",\"id-nr\":\"6\",\"class\":\"storage\",\"claimed\":true,\"handle\":\"PCI:0000:00:01.1\",\"description\":\"IDE interface\",\"product\":\"82371AB/EB/MB PIIX4 IDE\",\"vendor\":\"Intel Corporation\",\"physid\":\"1.1\",\"businfo\":\"pci@0000:00:01.1\",\"version\":\"01\",\"width\":32,\"clock\":33000000,\"configuration\":{\"driver\":\"ata_piix\",\"latency\":\"64\"},\"capabilities\":{\"ide\":true,\"bus_master\":\"bus mastering\"}},{\"id\":\"display\",\"id-nr\":\"7\",\"class\":\"display\",\"handle\":\"PCI:0000:00:02.0\",\"description\":\"VGA compatible controller\",\"product\":\"VirtualBox Graphics Adapter\",\"vendor\":\"InnoTek Systemberatung GmbH\",\"physid\":\"2\",\"businfo\":\"pci@0000:00:02.0\",\"version\":\"00\",\"width\":32,\"clock\":33000000,\"configuration\":{\"latency\":\"0\"},\"capabilities\":{\"vga_controller\":true,\"bus_master\":\"bus mastering\"}},{\"id\":\"network\",\"id-nr\":\"8\",\"class\":\"network\",\"claimed\":true,\"handle\":\"PCI:0000:00:03.0\",\"description\":\"Ethernet interface\",\"product\":\"82540EM Gigabit Ethernet Controller\",\"vendor\":\"Intel Corporation\",\"physid\":\"3\",\"businfo\":\"pci@0000:00:03.0\",\"logicalname\":\"eth0\",\"version\":\"02\",\"serial\":\"[REMOVED]\",\"units\":\"bit/s\",\"size\":1000000000,\"capacity\":1000000000,\"width\":32,\"clock\":66000000,\"configuration\":{\"autonegotiation\":\"on\",\"broadcast\":\"yes\",\"driver\":\"e1000\",\"driverversion\":\"7.3.21-k8-NAPI\",\"duplex\":\"full\",\"ip\":\"[REMOVED]\",\"latency\":\"64\",\"link\":\"yes\",\"mingnt\":\"255\",\"multicast\":\"yes\",\"port\":\"twisted pair\",\"speed\":\"1Gbit/s\"},\"capabilities\":{\"pm\":\"Power Management\",\"pcix\":\"PCI-X\",\"bus_master\":\"bus mastering\",\"cap_list\":\"PCI capabilities listing\",\"ethernet\":true,\"physical\":\"Physical interface\",\"tp\":\"twisted pair\",\"10bt\":\"10Mbit/s\",\"10bt-fd\":\"10Mbit/s (full duplex)\",\"100bt\":\"100Mbit/s\",\"100bt-fd\":\"100Mbit/s (full duplex)\",\"1000bt-fd\":\"1Gbit/s (full duplex)\",\"autonegotiation\":\"Auto-negotiation\"}},{\"id\":\"generic\",\"id-nr\":\"9\",\"class\":\"generic\",\"claimed\":true,\"handle\":\"PCI:0000:00:04.0\",\"description\":\"System peripheral\",\"product\":\"VirtualBox Guest Service\",\"vendor\":\"InnoTek Systemberatung GmbH\",\"physid\":\"4\",\"businfo\":\"pci@0000:00:04.0\",\"version\":\"00\",\"width\":32,\"clock\":33000000,\"configuration\":{\"driver\":\"vboxguest\",\"latency\":\"0\"},\"capabilities\":{\"bus_master\":\"bus mastering\"}},{\"id\":\"bridge\",\"id-nr\":\"10\",\"class\":\"bridge\",\"handle\":\"PCI:0000:00:07.0\",\"description\":\"Bridge\",\"product\":\"82371AB/EB/MB PIIX4 ACPI\",\"vendor\":\"Intel Corporation\",\"physid\":\"7\",\"businfo\":\"pci@0000:00:07.0\",\"version\":\"08\",\"width\":32,\"clock\":33000000,\"configuration\":{\"latency\":\"0\"},\"capabilities\":{\"bridge\":true,\"bus_master\":\"bus mastering\"}}]},{\"id\":\"scsi\",\"id-nr\":\"11\",\"class\":\"storage\",\"claimed\":true,\"physid\":\"2\",\"logicalname\":\"scsi0\",\"capabilities\":{\"emulated\":\"Emulated device\"},\"children\":[{\"id\":\"disk\",\"id-nr\":\"12\",\"class\":\"disk\",\"claimed\":true,\"handle\":\"SCSI:00:00:00:00\",\"description\":\"ATA Disk\",\"product\":\"VBOX HARDDISK\",\"physid\":\"0.0.0\",\"businfo\":\"scsi@0:0.0.0\",\"logicalname\":\"/dev/sda\",\"dev\":\"8:0\",\"version\":\"1.0\",\"serial\":\"[REMOVED]\",\"units\":\"bytes\",\"size\":10632560640,\"configuration\":{\"ansiversion\":\"5\",\"logicalsectorsize\":\"512\",\"sectorsize\":\"512\",\"signature\":\"c1250823\"},\"capabilities\":{\"partitioned\":\"Partitioned disk\",\"partitioned:dos\":\"MS-DOS partition table\"},\"children\":[{\"id\":\"volume:0\",\"id-nr\":\"13\",\"class\":\"volume\",\"claimed\":true,\"description\":\"EXT4 volume\",\"vendor\":\"Linux\",\"physid\":\"1\",\"businfo\":\"scsi@0:0.0.0,1\",\"logicalname\":[\"/dev/sda1\",\"/\"],\"dev\":\"8:1\",\"version\":\"1.0\",\"serial\":\"[REMOVED]\",\"size\":10144972800,\"capacity\":10144972800,\"configuration\":{\"created\":\"2015-10-21 18:04:06\",\"filesystem\":\"ext4\",\"lastmountpoint\":\"/\",\"modified\":\"2016-10-25 11:52:53\",\"mount.fstype\":\"ext4\",\"mount.options\":\"rw,relatime,errors=remount-ro,data=ordered\",\"mounted\":\"2016-10-24 09:05:31\",\"state\":\"mounted\"},\"capabilities\":{\"primary\":\"Primary partition\",\"bootable\":\"Bootable partition (active)\",\"journaled\":true,\"extended_attributes\":\"Extended Attributes\",\"large_files\":\"4GB+ files\",\"huge_files\":\"16TB+ files\",\"dir_nlink\":\"directories with 65000+ subdirs\",\"extents\":\"extent-based allocation\",\"ext4\":true,\"ext2\":\"EXT2/EXT3\",\"initialized\":\"initialized volume\"}},{\"id\":\"volume:1\",\"id-nr\":\"14\",\"class\":\"volume\",\"claimed\":true,\"description\":\"Extended partition\",\"physid\":\"2\",\"businfo\":\"scsi@0:0.0.0,2\",\"logicalname\":\"/dev/sda2\",\"dev\":\"8:2\",\"size\":484443136,\"capacity\":484443136,\"capabilities\":{\"primary\":\"Primary partition\",\"extended\":\"Extended partition\",\"partitioned\":\"Partitioned disk\",\"partitioned:extended\":\"Extended partition\"},\"children\":[{\"id\":\"logicalvolume\",\"id-nr\":\"15\",\"class\":\"volume\",\"claimed\":true,\"description\":\"Linux swap / Solaris partition\",\"physid\":\"5\",\"logicalname\":\"/dev/sda5\",\"dev\":\"8:5\",\"capacity\":484442112,\"capabilities\":{\"nofs\":\"No filesystem\"}}]}]}]}]}]}").toOption.get
    val common = JsonMatcher.compare(json1, json2)
    val devices@Seq(d1, d2) =
      genConflictFreeDeviceTs(2).sample.get.map(createDeviceOk(_))
    updateSystemInfo(d1, json1) ~> route ~> check {
      status shouldBe OK
    }
    updateSystemInfo(d2, json2) ~> route ~> check {
      status shouldBe OK
    }

    createGroupFromDevices(d1, d2, arbitrary[GroupInfo.Name].sample.get) ~> route ~> check {
      status shouldBe OK
      val groupId = responseAs[Uuid]
      fetchGroupInfo(groupId) ~> route ~> check {
        status shouldBe OK
        responseAs[Json] shouldEqual common._1
      }
      updateSystemInfo(d1, json2) ~> route ~> check {
        status shouldBe OK
      }
      Thread.sleep(5000)
        listDevicesInGroup(groupId) ~> route ~> check {
          status shouldBe OK
          responseAs[Seq[Uuid]] should contain (d1)
        }
    }
  }

}
