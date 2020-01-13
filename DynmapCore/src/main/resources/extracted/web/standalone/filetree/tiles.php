<?php
// Used for interacting with tiles

include('authentication.php');

switch (strtolower($_REQUEST["action"] ?? null)) {
	case "write":
		writeTile();
		break;
	case "delete":
		deleteTile();
		break;
	case "purge":
		purgeTiles();
		break;
	case "enumtiles":
		enumTiles();
		break;
	case "enumzoom":
		enumZoomLevels();
		break;
	default:
		response("Invalid or missing 'action'", 400);
		break; // bad request
}

/**
 * Enforces that the request has a valid key sent with it
 * */
function checkAuthentication()
{
	if (!authenticateUser()) {
		response(null, 401);	// unauthorized
		exit();
	}
}

/**
 * Enforces that the specified method be used for the request, exits the script if otherwise
 * @param string $method The method to be used
 * */
function checkMethod(string $method)
{
	if ($_SERVER["REQUEST_METHOD"] != $method) {
		response(null, 405);	// method not allowed
		exit();
	}
}

/**
 * Writes a new or updated tile to the disk
 * */
function writeTile()
{
	checkAuthentication();
	checkMethod("POST");

	$tile = [
		"world" => $_REQUEST["world"] ?? null,
		"map_prefix" => $_REQUEST["map_prefix"] ?? null,
		"x" => $_REQUEST["x"] ?? null,
		"y" => $_REQUEST["y"] ?? null,
		"zoom" => $_REQUEST["zoom"] ?? null,
		"hash" => $_REQUEST["hash"] ?? null,
		"file_type" => $_REQUEST["file_type"] ?? null
	];

	$file = $_FILES["file"] ?? null;	// get reference to uploaded file

	// make sure no parameters were left null
	foreach ($tile as $k => $v) {
		if (!isset($v)) {
			response("Missing parameter: $k", 400);	// bad request
			return;
		}
	}

	// ensure file is uploaded
	if (!is_array($file)) {
		response("File upload missing", 400);
		return;
	}

	// create paths for the newly created file
	$tile_file_container = getTileContainer($tile);
	$tile_file_name = getTileFileNameNoExt($tile);

	// create tile container if it doesn't exist
	if (!file_exists($tile_file_container)) {
		mkdir($tile_file_container, 0777, true);
	}

	// move uploaded tile file to location
	try {
		if (move_uploaded_file($file["tmp_name"], $tile_file_container . $tile_file_name . "." . $tile["file_type"])) {
			// success, write file hash
			$hash_file = fopen($tile_file_container . $tile_file_name . ".hash", "w");
			if (!$hash_file || !fwrite($hash_file, $tile["hash"])) {
				// failed to write hash, delete image file
				unlink($tile_file_container . $tile_file_name);
				throw new Exception("Failed to write tile hash");
			}
		} else {
			throw new Exception("Failed to write tile image file");
		}
	} catch (Exception $ex) {
		response($ex->getMessage(), 500);
		return;
	}

	response(null, 200);	// success
}

/**
 * Deletes a tile from the disk if it exists
 * */
function deleteTile()
{
	checkAuthentication();
	checkMethod("POST");

	$tile = [
		"world" => $_REQUEST["world"] ?? null,
		"map_prefix" => $_REQUEST["map_prefix"] ?? null,
		"x" => $_REQUEST["x"] ?? null,
		"y" => $_REQUEST["y"] ?? null,
		"zoom" => $_REQUEST["zoom"] ?? null,
		"file_type" => $_REQUEST["file_type"] ?? null
	];

	// make sure no parameters were left null
	foreach ($tile as $k => $v) {
		if (!isset($v)) {
			response("Missing parameter: $k", 400);	// bad request
			return;
		}
	}

	$tile_file_name = getTileContainer($tile) . getTileFileNameNoExt($tile);
	$files = [$tile_file_name . "." . $tile["file_type"], $tile_file_name . ".hash"];

	// delete image and hash
	foreach ($files as $file) {
		if (file_exists($file)) {
			if (!unlink($file)) {
				response("Failed to delete file " . $file, 500);
			}
		}
	}
}

/**
 * Deletes ALL tiles from the disk for the specified world and map
 * */
function purgeTiles()
{
	checkAuthentication();
	checkMethod("POST");

	$map = [
		"world" => $_REQUEST["world"] ?? null,
		"map_prefix" => $_REQUEST["map_prefix"] ?? null
	];

	// make sure no parameters were left null
	foreach ($map as $k => $v) {
		if (!isset($v)) {
			response("Missing parameter: $k", 400);	// bad request
			return;
		}
	}

	$map_container = sprintf("%s/tiles/%s/%s/", dirname(__FILE__), $map["world"], $map["map_prefix"]);

	if (is_dir($map_container)) {
		deleteTree($map_container);	// recursively delete all files for map
	}
}

/**
 * Sends a list of all the tiles for a specified world, map, and (optional) zoom in a list
 * */
function enumTiles()
{
	checkMethod("GET");

	$tile = [
		"world" => $_REQUEST["world"] ?? null,
		"map_prefix" => $_REQUEST["map_prefix"] ?? null,
		"zoom" => $_REQUEST["zoom"] ?? null
	];

	// make sure no parameters were left null
	foreach ($tile as $k => $v) {
		if (!isset($v)) {
			response("Missing parameter: $k", 400);	// bad request
			return;
		}
	}

	$container = getTileContainer($tile);
	header("Content-Type: text/plain");

	if (is_dir($container)) {
		foreach (scandir($container) as $entry) {
			if (!is_dir($entry)) {
				echo ($entry . "\n");
			}
		}
	}
}

/**
 * Sends a list of all the zoom levels for a specified world and map
 * */
function enumZoomLevels()
{
	checkMethod("GET");

	$tile = [
		"world" => $_REQUEST["world"] ?? null,
		"map_prefix" => $_REQUEST["map_prefix"] ?? null,
		"zoom" => 0	// dummy value
	];

	// make sure no parameters were left null
	foreach ($tile as $k => $v) {
		if (!isset($v)) {
			response("Missing parameter: $k", 400);	// bad request
			return;
		}
	}

	$container = dirname(getTileContainer($tile), 1);	// get parent directory of tile container (directory) with zoom levels
	header("Content-Type: text/plain");

	if (is_dir($container)) {
		foreach (scandir($container) as $entry) {
			if (!is_file($entry) && $entry != "." && $entry != "..") {
				echo ($entry . "\n");
			}
		}
	}
}

function getTileContainer($tile)
{
	// /var/www/standalone/filetree/tiles/world/flat/0/
	return sprintf("%s/tiles/%s/%s/%s/", dirname(__FILE__), $tile["world"], $tile["map_prefix"], $tile["zoom"]);
}

function getTileFileNameNoExt($tile)
{
	// 460.69
	return sprintf("%s.%s", $tile["x"], $tile["y"]);
}

function response($body, $code = 200)
{
	http_response_code($code);
	echo ($body . "\n");
}

function deleteTree($dir)
{
	$files = array_diff(scandir($dir), array('.', '..'));
	foreach ($files as $file) {
		(is_dir("$dir/$file")) ? deleteTree("$dir/$file") : unlink("$dir/$file");
	}
	return rmdir($dir);
}
